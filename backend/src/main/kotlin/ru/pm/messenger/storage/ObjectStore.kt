package ru.pm.messenger.storage

import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

interface ObjectStore {
    fun put(key: String, bytes: ByteArray, contentType: String)

    fun get(key: String): ByteArray
}

@Component
@Profile("test")
class MemoryObjectStore : ObjectStore {
    private val objects = ConcurrentHashMap<String, ByteArray>()

    override fun put(key: String, bytes: ByteArray, contentType: String) {
        objects[key] = bytes.copyOf()
    }

    override fun get(key: String): ByteArray = objects[key]?.copyOf()
        ?: throw IllegalStateException("Object is missing.")
}

@Component
@Profile("!test")
class S3ObjectStore(
    @Value("\${app.storage.endpoint}") endpoint: String,
    @Value("\${app.storage.access-key}") accessKey: String,
    @Value("\${app.storage.secret-key}") secretKey: String,
    @Value("\${app.storage.bucket}") private val bucket: String,
) : ObjectStore, AutoCloseable {
    private val client: S3Client

    init {
        require(endpoint.isNotBlank() && accessKey.isNotBlank() && secretKey.isNotBlank() && bucket.isNotBlank()) {
            "Object storage is not configured"
        }
        client = S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)),
            )
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()
    }

    @PostConstruct
    fun ensureBucket() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
        } catch (_: NoSuchBucketException) {
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
        } catch (error: S3Exception) {
            if (error.statusCode() == 404) {
                client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
            } else {
                throw error
            }
        }
    }

    override fun put(key: String, bytes: ByteArray, contentType: String) {
        client.putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
            RequestBody.fromBytes(bytes),
        )
    }

    override fun get(key: String): ByteArray =
        client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build()).use { stream ->
            stream.readBytes()
        }

    override fun close() {
        client.close()
    }
}
