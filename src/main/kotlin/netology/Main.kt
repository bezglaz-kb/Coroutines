package netology

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import kotlinx.coroutines.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import java.time.Instant

private const val BASE_URL = "http://localhost:9999"

data class Post(
    val id: Long,
    val authorId: Long,
    val content: String,
    val published: Long,
    val likedByMe: Boolean,
    val likes: Int = 0,
    var attachment: Attachment? = null,
)

data class Attachment(
    val url: String,
    val description: String,
    val type: AttachmentType,
)

enum class AttachmentType {
    IMAGE, VIDEO, AUDIO
}

data class Comment(
    val id: Long,
    val postId: Long,
    val authorId: Long,
    val content: String,
    val published: Long,
    val likedByMe: Boolean,
    val likes: Int = 0,
)

data class Author(
    val id: Long,
    val name: String,
    val avatar: String,
)

data class PostWithAuthor(val post: Post, val author: Author)
data class CommentWithAuthor(val comment: Comment, val author: Author)
data class FullPostData(val postWithAuthor: PostWithAuthor, val comments: List<CommentWithAuthor>)

interface NetologyApi {
    @GET("api/posts")
    suspend fun getPosts(): List<Post>

    @GET("api/authors/{id}")
    suspend fun getAuthorById(@Path("id") id: Long): Author

    @GET("api/posts/{id}/comments")
    suspend fun getCommentsByPostId(@Path("id") id: Long): List<Comment>
}

private val customGson = GsonBuilder()
    .registerTypeAdapter(Long::class.java, JsonDeserializer { json, _, _ ->
        try {
            Instant.parse(json.asString).toEpochMilli()
        } catch (e: Exception) {
            json.asLong
        }
    })

    .registerTypeAdapter(Long::class.java, JsonSerializer<Long> { src, _, _ ->
        try {
            JsonPrimitive(Instant.ofEpochMilli(src).toString())
        } catch (e: Exception) {
            JsonPrimitive(src)
        }
    })
    .create()


private val retrofit = Retrofit.Builder()
    .baseUrl(BASE_URL)
    .addConverterFactory(GsonConverterFactory.create(customGson))
    .build()

val apiService: NetologyApi = retrofit.create(NetologyApi::class.java)


suspend fun loadFullData(): List<FullPostData> = coroutineScope {
    val posts = apiService.getPosts()

       val deferredPosts = posts.map { post ->
        async {
            val authorDeferred = async { apiService.getAuthorById(post.authorId) }
            val commentsDeferred = async {
                try {
                    apiService.getCommentsByPostId(post.id)
                } catch (e: Exception) {
                    emptyList<Comment>()
                }
            }

            val author = authorDeferred.await()
            val comments = commentsDeferred.await()

            val commentsWithAuthorsDeferred = comments.map { comment ->
                async {
                    val commentAuthor = apiService.getAuthorById(comment.authorId)
                    CommentWithAuthor(comment, commentAuthor)
                }
            }

            val commentsWithAuthors = commentsWithAuthorsDeferred.awaitAll()

            FullPostData(
                postWithAuthor = PostWithAuthor(post, author),
                comments = commentsWithAuthors
            )
        }
    }

    return@coroutineScope deferredPosts.awaitAll()
}

fun main() {
    runBlocking {
        try {
            val result = loadFullData()

            result.forEach { fullPost ->
                println("Пост №${fullPost.postWithAuthor.post.id} от автора: ${fullPost.postWithAuthor.author.name}")
                println("Текст: ${fullPost.postWithAuthor.post.content}")

                if (fullPost.comments.isNotEmpty()) {
                    println("  Комментарии (${fullPost.comments.size}):")
                    fullPost.comments.forEach { commentWithAuthor ->
                        println("    - [${commentWithAuthor.author.name}]: ${commentWithAuthor.comment.content}")
                    }
                } else {
                    println("  Комментариев нет.")
                }
            }
        } catch (e: Exception) {
            println("Произошла ошибка сети:")
            e.printStackTrace()
        }
    }
}