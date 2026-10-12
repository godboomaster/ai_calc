package com.smartcalc.app.data.library

import kotlinx.serialization.Serializable
import com.smartcalc.app.data.library.source.SourceSegmentRef

const val LIBRARY_METADATA_SCHEMA_VERSION = 1

@Serializable
data class LibraryMetadata(
    val schemaVersion: Int = LIBRARY_METADATA_SCHEMA_VERSION,
    val id: String,
    val version: Int = 1,
    val origin: LibraryOrigin = LibraryOrigin(LibraryOriginKind.LEGACY),
    val verification: LibraryVerification = LibraryVerification.UNVERIFIED,
    val enabled: Boolean = true,
    val previousVersions: List<LibraryRevision> = emptyList(),
    val sourceRefs: List<SourceSegmentRef> = emptyList(),
)

@Serializable
data class LibraryOrigin(
    val kind: LibraryOriginKind,
    val name: String? = null,
)

@Serializable
enum class LibraryOriginKind { LEGACY, MANUAL, FILE_IMPORT }

@Serializable
enum class LibraryVerification { UNVERIFIED, VERIFIED_BY_USER, REJECTED }

@Serializable
data class LibraryRevision(
    val version: Int,
    val condition: String,
    val conditionImages: List<String>,
    val solution: String,
    val solutionImages: List<String>,
    val verification: LibraryVerification,
    val sourceRefs: List<SourceSegmentRef> = emptyList(),
)
