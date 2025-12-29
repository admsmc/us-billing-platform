package com.example.usbilling.document.storage

import java.io.InputStream

/**
 * Document storage abstraction.
 * Supports local filesystem and S3-compatible storage.
 */
interface DocumentStorage {

    /**
     * Store a document.
     *
     * @param key Unique storage key
     * @param content Document content as byte array
     * @param contentType MIME type
     * @return Storage location URL or key
     */
    fun store(key: String, content: ByteArray, contentType: String): String

    /**
     * Retrieve a document.
     *
     * @param key Storage key
     * @return Document content as input stream, or null if not found
     */
    fun retrieve(key: String): InputStream?

    /**
     * Delete a document.
     *
     * @param key Storage key
     * @return true if deleted, false if not found
     */
    fun delete(key: String): Boolean

    /**
     * Check if a document exists.
     *
     * @param key Storage key
     * @return true if exists, false otherwise
     */
    fun exists(key: String): Boolean
}
