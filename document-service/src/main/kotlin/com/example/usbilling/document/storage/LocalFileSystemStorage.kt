package com.example.usbilling.document.storage

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Local filesystem document storage implementation.
 * Used for development and testing.
 */
@Component
@ConditionalOnProperty(name = ["document.storage.type"], havingValue = "local", matchIfMissing = true)
@ConfigurationProperties(prefix = "document.storage.local")
class LocalFileSystemStorage : DocumentStorage {
    
    private val logger = LoggerFactory.getLogger(javaClass)
    
    var baseDirectory: String = System.getProperty("user.home") + "/document-storage"
    
    init {
        // Create base directory if it doesn't exist
        val dir = File(baseDirectory)
        if (!dir.exists()) {
            dir.mkdirs()
            logger.info("Created document storage directory: $baseDirectory")
        }
    }
    
    override fun store(key: String, content: ByteArray, contentType: String): String {
        val path = Paths.get(baseDirectory, key)
        
        // Create parent directories if needed
        path.parent?.let { Files.createDirectories(it) }
        
        Files.write(path, content)
        
        logger.info("Stored document: $key (${content.size} bytes, $contentType)")
        
        return path.toString()
    }
    
    override fun retrieve(key: String): InputStream? {
        val path = Paths.get(baseDirectory, key)
        
        return if (Files.exists(path)) {
            Files.newInputStream(path)
        } else {
            logger.warn("Document not found: $key")
            null
        }
    }
    
    override fun delete(key: String): Boolean {
        val path = Paths.get(baseDirectory, key)
        
        return if (Files.exists(path)) {
            Files.delete(path)
            logger.info("Deleted document: $key")
            true
        } else {
            logger.warn("Cannot delete - document not found: $key")
            false
        }
    }
    
    override fun exists(key: String): Boolean {
        val path = Paths.get(baseDirectory, key)
        return Files.exists(path)
    }
}
