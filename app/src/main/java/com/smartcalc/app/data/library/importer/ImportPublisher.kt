package com.smartcalc.app.data.library.importer

interface ImportPublisher {
    suspend fun publish(intent: PublicationIntent): ImportReceipt
    fun receipt(commitId: String): ImportReceipt?
}
