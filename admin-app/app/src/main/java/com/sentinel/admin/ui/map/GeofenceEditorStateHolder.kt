package com.sentinel.admin.ui.map

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Operational mode for draft loading to distinguish pristine form initialization
 * from non-destructive restored baseline reconciliation.
 */
enum class DraftLoadMode {
    INITIAL_PRISTINE_FORM,
    RECONCILE_RESTORED_BASELINE,
}

/**
 * Serializable inputs representing editable persisted fields.
 * Excludes UI status, dirty flags, revisions, timestamps, and operation state from semantic equality.
 */
data class EditorInputs(
    val selectedType: String = "CIRCLE",
    val circleLat: String = "",
    val circleLon: String = "",
    val circleRadius: String = "150",
    val polygonVertices: List<Pair<Double, Double>> = emptyList()
) : java.io.Serializable {
    fun isBlank(): Boolean {
        return if (selectedType == "CIRCLE") {
            circleLat.isBlank() && circleLon.isBlank()
        } else {
            polygonVertices.isEmpty()
        }
    }

    /**
     * Semantic equality comparing numeric coordinates and radii appropriately,
     * so that equivalent representations such as "150" and "150.0" do not falsely report dirty.
     * Invalid inputs compare via exact string equality.
     */
    fun semanticallyEquals(other: EditorInputs?): Boolean {
        if (other == null) return false
        if (selectedType != other.selectedType) return false
        return if (selectedType == "CIRCLE") {
            val lat1 = circleLat.toDoubleOrNull()
            val lat2 = other.circleLat.toDoubleOrNull()
            val latMatch = if (lat1 != null && lat2 != null) lat1 == lat2 else circleLat == other.circleLat

            val lon1 = circleLon.toDoubleOrNull()
            val lon2 = other.circleLon.toDoubleOrNull()
            val lonMatch = if (lon1 != null && lon2 != null) lon1 == lon2 else circleLon == other.circleLon

            val rad1 = circleRadius.toDoubleOrNull()
            val rad2 = other.circleRadius.toDoubleOrNull()
            val radMatch = if (rad1 != null && rad2 != null) rad1 == rad2 else circleRadius == other.circleRadius

            latMatch && lonMatch && radMatch
        } else {
            if (polygonVertices.size != other.polygonVertices.size) return false
            for (i in polygonVertices.indices) {
                val p1 = polygonVertices[i]
                val p2 = other.polygonVertices[i]
                if (p1.first != p2.first || p1.second != p2.second) return false
            }
            true
        }
    }
}

/**
 * Immutable ticket capturing submission context to guard IO against later input and owner changes.
 */
data class SaveTicket(
    val deviceKey: String,
    val ownerEpoch: Long,
    val operationId: Long,
    val submittedRevision: Long,
    val submittedInputs: EditorInputs,
    val draft: GeofenceDraft
)

data class DeleteTicket(
    val deviceKey: String,
    val ownerEpoch: Long,
    val operationId: Long
)

data class LoadTicket(
    val deviceKey: String,
    val ownerEpoch: Long,
    val operationId: Long,
    val revisionAtLaunch: Long,
    val mode: DraftLoadMode
)

/**
 * Bounded Undo history snapshot storing editable inputs and mutation revision.
 */
data class EditorSnapshot(
    val inputs: EditorInputs,
    val revision: Long = 0L
) : java.io.Serializable {
    fun toMap(): Map<String, Any?> = mapOf(
        "type" to inputs.selectedType,
        "lat" to inputs.circleLat.take(MAX_FIELD_LENGTH),
        "lon" to inputs.circleLon.take(MAX_FIELD_LENGTH),
        "rad" to inputs.circleRadius.take(MAX_FIELD_LENGTH),
        "vertices" to inputs.polygonVertices.take(MAX_POLYGON_VERTICES).map { listOf(it.first, it.second) },
        "rev" to revision
    )

    fun serialize(): String {
        val vStr = inputs.polygonVertices.joinToString(";") { "${it.first},${it.second}" }
        return "${inputs.selectedType}|${inputs.circleLat}|${inputs.circleLon}|${inputs.circleRadius}|$vStr|$revision"
    }

    companion object {
        const val MAX_FIELD_LENGTH = 128
        const val MAX_POLYGON_VERTICES = 100
        const val MAX_HISTORY = 20

        fun fromMap(map: Map<String, Any?>): EditorSnapshot? {
            val type = (map["type"] as? String) ?: "CIRCLE"
            val lat = (map["lat"] as? String)?.take(MAX_FIELD_LENGTH) ?: ""
            val lon = (map["lon"] as? String)?.take(MAX_FIELD_LENGTH) ?: ""
            val rad = (map["rad"] as? String)?.take(MAX_FIELD_LENGTH) ?: "150"
            val rawVertices = map["vertices"] as? List<*>
            val vertices = rawVertices?.mapNotNull { item ->
                when (item) {
                    is List<*> -> {
                        val latVal = (item.getOrNull(0) as? Number)?.toDouble()
                        val lonVal = (item.getOrNull(1) as? Number)?.toDouble()
                        if (latVal != null && lonVal != null) latVal to lonVal else null
                    }
                    is String -> {
                        val parts = item.split(",")
                        val latVal = parts.getOrNull(0)?.toDoubleOrNull()
                        val lonVal = parts.getOrNull(1)?.toDoubleOrNull()
                        if (latVal != null && lonVal != null) latVal to lonVal else null
                    }
                    else -> null
                }
            }?.take(MAX_POLYGON_VERTICES) ?: emptyList()
            val rev = (map["rev"] as? Number)?.toLong() ?: 0L
            return EditorSnapshot(
                inputs = EditorInputs(type, lat, lon, rad, vertices),
                revision = rev
            )
        }

        fun deserialize(str: String): EditorSnapshot? {
            val parts = str.split("|")
            if (parts.size < 5) return null
            val type = parts[0]
            val lat = parts[1].take(MAX_FIELD_LENGTH)
            val lon = parts[2].take(MAX_FIELD_LENGTH)
            val rad = parts[3].take(MAX_FIELD_LENGTH)
            val vParts = parts[4].split(";").filter { it.isNotBlank() }.take(MAX_POLYGON_VERTICES)
            val vertices = vParts.mapNotNull { p ->
                val coords = p.split(",")
                if (coords.size == 2) {
                    val cLat = coords[0].toDoubleOrNull()
                    val cLon = coords[1].toDoubleOrNull()
                    if (cLat != null && cLon != null) cLat to cLon else null
                } else null
            }
            val rev = if (parts.size >= 6) parts[5].toLongOrNull() ?: 0L else 0L
            return EditorSnapshot(
                inputs = EditorInputs(type, lat, lon, rad, vertices),
                revision = rev
            )
        }
    }
}

/**
 * Production state holder managing geofence draft editing lifecycle across tab switching and recreation.
 *
 * Implements Pass 7 requirements:
 * - Screen-owned persistence lifecycle: Requires an explicit ownerScope; no public tab-scope overrides
 * - Mutual exclusion & busy-state ownership: Queued waiters do not overwrite running operation's token
 * - Atomic reset: Old background work settles cleanly without leaking busy state or metadata to new device
 * - Restoration reconciliation: DraftLoadMode distinguishes pristine form population from non-destructive baseline sync
 * - Complete restoration: Restores initial comparison baseline explicitly; bounded lossless serialization
 * - Monotonic elapsed clock for 800 ms undo typing-coalescing
 * - Numeric semantic equality for floats/doubles (e.g. 150 == 150.0)
 */
class GeofenceEditorStateHolder(
    initialDeviceId: String,
    val draftStore: GeofenceDraftStore,
    val ownerScope: CoroutineScope,
    fallbackLat: Double? = null,
    fallbackLon: Double? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clockMs: () -> Long = {
        try {
            android.os.SystemClock.elapsedRealtime()
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }
) {
    var deviceId by mutableStateOf(initialDeviceId)
        private set

    var ownerEpoch by mutableLongStateOf(0L)
    var editorRevision by mutableLongStateOf(0L)
    var persistedRevision by mutableLongStateOf(0L)
    var isDirty by mutableStateOf(false)

    var selectedType by mutableStateOf("CIRCLE")

    // Circle inputs
    var circleLatInput by mutableStateOf(fallbackLat?.toString() ?: "")
    var circleLonInput by mutableStateOf(fallbackLon?.toString() ?: "")
    var circleRadiusInput by mutableStateOf("150")

    // Polygon vertices
    val polygonVertices = mutableStateListOf<Pair<Double, Double>>()

    // Persisted baseline established by storage load or successful commit
    var persistedInputs by mutableStateOf<EditorInputs?>(null)
    var hasPersistedDraft by mutableStateOf(false)

    // Bounded Undo history (max 20 snapshots)
    val historyStack = mutableStateListOf<EditorSnapshot>()

    // Initial comparison baseline
    var initialInputs: EditorInputs = EditorInputs(
        selectedType = "CIRCLE",
        circleLat = fallbackLat?.toString() ?: "",
        circleLon = fallbackLon?.toString() ?: "",
        circleRadius = "150",
        polygonVertices = emptyList()
    )

    var needsBaselineReconciliation by mutableStateOf(false)

    var lastEditedField: String? = null
    var lastEditTimestamp: Long = 0L

    var validationError by mutableStateOf<String?>(null)
    var statusMessage by mutableStateOf<String?>(null)
    var isSaving by mutableStateOf(false)
    var isLoading by mutableStateOf(false)

    private val operationMutex = Mutex()
    private var nextOperationId = 0L
    private var lastCompletedOperationId = 0L
    private var activeSavingOpId = 0L
    private var activeLoadingOpId = 0L
    private var activeOperationJob: kotlinx.coroutines.Job? = null

    fun currentPersistedInputs(): EditorInputs {
        return EditorInputs(
            selectedType = selectedType,
            circleLat = circleLatInput,
            circleLon = circleLonInput,
            circleRadius = circleRadiusInput,
            polygonVertices = polygonVertices.toList()
        )
    }

    fun recomputeDirtyState() {
        isDirty = if (persistedInputs != null) {
            !currentPersistedInputs().semanticallyEquals(persistedInputs)
        } else {
            !currentPersistedInputs().semanticallyEquals(initialInputs)
        }
    }

    private fun isCurrentOwner(ticketDeviceKey: String, ticketOwnerEpoch: Long): Boolean {
        return ticketDeviceKey == deviceId && ticketOwnerEpoch == ownerEpoch
    }

    fun restoreEditableFields(inputs: EditorInputs) {
        selectedType = inputs.selectedType
        circleLatInput = inputs.circleLat
        circleLonInput = inputs.circleLon
        circleRadiusInput = inputs.circleRadius
        polygonVertices.clear()
        polygonVertices.addAll(inputs.polygonVertices)
        validationError = null
    }

    /**
     * Called before any user modification.
     * Always increments editorRevision.
     * Coalesces Undo snapshots only when typing rapidly in the same field within 800 ms.
     */
    fun beforeUserMutation(fieldName: String? = null) {
        val now = clockMs()
        val elapsed = now - lastEditTimestamp
        val isCoalescing = fieldName != null && fieldName == lastEditedField && elapsed in 0L until 800L
        if (!isCoalescing) {
            if (historyStack.size >= EditorSnapshot.MAX_HISTORY) {
                historyStack.removeAt(0)
            }
            historyStack.add(EditorSnapshot(currentPersistedInputs(), editorRevision))
            lastEditedField = fieldName
        }
        lastEditTimestamp = now
        editorRevision++
    }

    fun onCircleLatChanged(newVal: String) {
        val bounded = newVal.take(EditorSnapshot.MAX_FIELD_LENGTH)
        if (bounded != circleLatInput) {
            beforeUserMutation("circleLat")
            circleLatInput = bounded
            recomputeDirtyState()
            validationError = null
        }
    }

    fun onCircleLonChanged(newVal: String) {
        val bounded = newVal.take(EditorSnapshot.MAX_FIELD_LENGTH)
        if (bounded != circleLonInput) {
            beforeUserMutation("circleLon")
            circleLonInput = bounded
            recomputeDirtyState()
            validationError = null
        }
    }

    fun onCircleRadiusChanged(newVal: String) {
        val bounded = newVal.take(EditorSnapshot.MAX_FIELD_LENGTH)
        if (bounded != circleRadiusInput) {
            beforeUserMutation("circleRadius")
            circleRadiusInput = bounded
            recomputeDirtyState()
            validationError = null
        }
    }

    fun onTypeSelected(type: String) {
        if (type != selectedType) {
            beforeUserMutation(null)
            selectedType = type
            recomputeDirtyState()
            validationError = null
        }
    }

    fun onAddPolygonVertex(lat: Double, lon: Double) {
        if (polygonVertices.size >= EditorSnapshot.MAX_POLYGON_VERTICES) return
        beforeUserMutation(null)
        polygonVertices.add(lat to lon)
        recomputeDirtyState()
        validationError = null
    }

    fun onRemovePolygonVertex(index: Int) {
        if (index in polygonVertices.indices) {
            beforeUserMutation(null)
            polygonVertices.removeAt(index)
            recomputeDirtyState()
            validationError = null
        }
    }

    /**
     * Undoes last edit from bounded history stack.
     * Reconstructs editable fields without restoring historical isDirty.
     * Derives dirty state by comparing against today's persisted baseline.
     */
    fun undo(): Boolean {
        if (historyStack.isEmpty()) return false
        val prior = historyStack.removeAt(historyStack.lastIndex)
        restoreEditableFields(prior.inputs)
        editorRevision++
        lastEditedField = null
        recomputeDirtyState()
        statusMessage = "Undid last edit"
        return true
    }

    /**
     * Clears user form inputs while leaving persisted storage completely intact.
     */
    fun clearForm() {
        beforeUserMutation(null)
        polygonVertices.clear()
        circleLatInput = ""
        circleLonInput = ""
        circleRadiusInput = "150"
        validationError = null
        recomputeDirtyState()
        statusMessage = "Form cleared (draft in storage untouched)"
    }

    /**
     * Loads draft asynchronously on Dispatchers.IO.
     * Captures ticket with ownerEpoch and revisionAtLaunch.
     * Respects DraftLoadMode to prevent clobbering restored edits during reconciliation.
     */
    fun loadDraft(mode: DraftLoadMode = DraftLoadMode.INITIAL_PRISTINE_FORM): kotlinx.coroutines.Job {
        val opId = ++nextOperationId
        val ticket = LoadTicket(
            deviceKey = deviceId,
            ownerEpoch = ownerEpoch,
            operationId = opId,
            revisionAtLaunch = editorRevision,
            mode = mode
        )

        val job = ownerScope.launch {
            var acquiredBusyOwnership = false
            try {
                operationMutex.withLock {
                    if (!isCurrentOwner(ticket.deviceKey, ticket.ownerEpoch)) return@withLock
                    activeLoadingOpId = ticket.operationId
                    isLoading = true
                    acquiredBusyOwnership = true

                    val draft = withContext(ioDispatcher) {
                        draftStore.getDraft(ticket.deviceKey)
                    }
                    if (isCurrentOwner(ticket.deviceKey, ticket.ownerEpoch) && ticket.operationId >= lastCompletedOperationId) {
                        lastCompletedOperationId = ticket.operationId
                        if (draft != null) {
                            val validation = when (draft) {
                                is GeofenceDraft.Circle -> GeofenceGeometryValidator.validateCircle(
                                    draft.centerLat,
                                    draft.centerLon,
                                    draft.radiusMeters
                                )
                                is GeofenceDraft.Polygon -> GeofenceGeometryValidator.validatePolygon(
                                    draft.vertices
                                )
                            }
                            if (validation.isValid) {
                                val loadedInputs = when (draft) {
                                    is GeofenceDraft.Circle -> EditorInputs(
                                        selectedType = "CIRCLE",
                                        circleLat = draft.centerLat.toString(),
                                        circleLon = draft.centerLon.toString(),
                                        circleRadius = draft.radiusMeters.toString(),
                                        polygonVertices = emptyList()
                                    )
                                    is GeofenceDraft.Polygon -> EditorInputs(
                                        selectedType = "POLYGON",
                                        circleLat = "",
                                        circleLon = "",
                                        circleRadius = "150",
                                        polygonVertices = draft.vertices
                                    )
                                }
                                hasPersistedDraft = true
                                persistedInputs = loadedInputs
                                persistedRevision = 0L

                                if (ticket.mode == DraftLoadMode.RECONCILE_RESTORED_BASELINE) {
                                    // RECONCILIATION: Update persisted baseline without replacing editable fields
                                    recomputeDirtyState()
                                    statusMessage = if (isDirty) {
                                        "Loaded baseline from storage; unsaved edits retained"
                                    } else {
                                        "Loaded saved ${draft.type.lowercase()} draft"
                                    }
                                } else {
                                    // INITIAL_PRISTINE_FORM: Populate form if no edits were made
                                    if (editorRevision == ticket.revisionAtLaunch) {
                                        restoreEditableFields(loadedInputs)
                                        editorRevision = 0L
                                        isDirty = false
                                        historyStack.clear()
                                        lastEditedField = null
                                        validationError = null
                                        statusMessage = "Loaded saved ${draft.type.lowercase()} draft"
                                    } else {
                                        recomputeDirtyState()
                                        statusMessage = "Loaded baseline from storage; unsaved edits retained"
                                    }
                                }
                            } else {
                                hasPersistedDraft = true
                                validationError = validation.errorMessage
                                statusMessage = "Stored draft failed geometry validation: ${validation.errorMessage}"
                            }
                        } else {
                            hasPersistedDraft = false
                            persistedInputs = null
                            recomputeDirtyState()
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (isCurrentOwner(ticket.deviceKey, ticket.ownerEpoch)) {
                    statusMessage = "Failed to load saved draft from storage: ${e.message ?: "Unknown IO error"}"
                }
            } finally {
                if (acquiredBusyOwnership &&
                    isCurrentOwner(ticket.deviceKey, ticket.ownerEpoch) &&
                    activeLoadingOpId == ticket.operationId
                ) {
                    activeLoadingOpId = 0L
                    isLoading = false
                }
            }
        }
        activeOperationJob = job
        return job
    }

    /**
     * Saves current draft to IO storage.
     * Respects Boolean commit result. Never marks clean or invokes onDraftSaved on failure.
     */
    fun saveDraft(onDraftSaved: (GeofenceDraft) -> Unit = {}): kotlinx.coroutines.Job? {
        val draftToSave: GeofenceDraft? = if (selectedType == "CIRCLE") {
            val lat = circleLatInput.toDoubleOrNull()
            val lon = circleLonInput.toDoubleOrNull()
            val rad = circleRadiusInput.toFloatOrNull()
            if (lat == null || lon == null || rad == null) {
                validationError = "Please enter valid numeric circle parameters"
                statusMessage = null
                return null
            }
            val validation = GeofenceGeometryValidator.validateCircle(lat, lon, rad)
            if (!validation.isValid) {
                validationError = validation.errorMessage
                statusMessage = null
                return null
            }
            GeofenceDraft.Circle(deviceId = deviceId, centerLat = lat, centerLon = lon, radiusMeters = rad)
        } else {
            val vertices = polygonVertices.toList()
            val validation = GeofenceGeometryValidator.validatePolygon(vertices)
            if (!validation.isValid) {
                validationError = validation.errorMessage
                statusMessage = null
                return null
            }
            GeofenceDraft.Polygon(deviceId = deviceId, vertices = vertices)
        }

        if (draftToSave == null) return null

        // Clear obsolete success messages at submission
        validationError = null
        statusMessage = null

        val opId = ++nextOperationId
        val ticket = SaveTicket(
            deviceKey = deviceId,
            ownerEpoch = ownerEpoch,
            operationId = opId,
            submittedRevision = editorRevision,
            submittedInputs = currentPersistedInputs(),
            draft = draftToSave
        )

        val job = ownerScope.launch {
            var acquiredBusyOwnership = false
            try {
                operationMutex.withLock {
                    if (!isCurrentOwner(ticket.deviceKey, ticket.ownerEpoch)) return@withLock
                    activeSavingOpId = ticket.operationId
                    isSaving = true
                    acquiredBusyOwnership = true

                    val committed = withContext(ioDispatcher) {
                        draftStore.saveDraft(ticket.draft)
                    }
                    if (!committed) {
                        if (isCurrentOwner(ticket.deviceKey, ticket.ownerEpoch) && ticket.operationId >= lastCompletedOperationId) {
                            lastCompletedOperationId = ticket.operationId
                            validationError = "Could not save draft to local storage"
                            statusMessage = "Could not save draft to local storage"
                        }
                        return@withLock
                    }

                    if (isCurrentOwner(ticket.deviceKey, ticket.ownerEpoch) && ticket.operationId >= lastCompletedOperationId) {
                        lastCompletedOperationId = ticket.operationId
                        persistedInputs = ticket.submittedInputs
                        persistedRevision = ticket.submittedRevision
                        hasPersistedDraft = true
                        recomputeDirtyState()
                        statusMessage = if (isDirty) {
                            "Draft saved locally (${ticket.draft.type.lowercase()}) with newer unsaved edits"
                        } else {
                            "Draft saved locally (${ticket.draft.type.lowercase()})"
                        }
                        validationError = null
                        onDraftSaved(ticket.draft)
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (isCurrentOwner(ticket.deviceKey, ticket.ownerEpoch)) {
                    validationError = "Failed to save draft to storage: ${e.message ?: "IO error"}"
                    statusMessage = validationError
                }
            } finally {
                if (acquiredBusyOwnership &&
                    isCurrentOwner(ticket.deviceKey, ticket.ownerEpoch) &&
                    activeSavingOpId == ticket.operationId
                ) {
                    activeSavingOpId = 0L
                    isSaving = false
                }
            }
        }
        activeOperationJob = job
        return job
    }

    /**
     * Deletes persisted draft record from storage on Dispatchers.IO.
     * Respects Boolean return value. Retains baseline on failed deletion.
     */
    fun deletePersistedDraft(): kotlinx.coroutines.Job {
        val opId = ++nextOperationId
        val ticket = DeleteTicket(
            deviceKey = deviceId,
            ownerEpoch = ownerEpoch,
            operationId = opId
        )

        validationError = null
        statusMessage = null

        val job = ownerScope.launch {
            var acquiredBusyOwnership = false
            try {
                operationMutex.withLock {
                    if (!isCurrentOwner(ticket.deviceKey, ticket.ownerEpoch)) return@withLock
                    activeSavingOpId = ticket.operationId
                    isSaving = true
                    acquiredBusyOwnership = true

                    val deleted = withContext(ioDispatcher) {
                        draftStore.deleteDraft(ticket.deviceKey)
                    }
                    if (!deleted) {
                        if (isCurrentOwner(ticket.deviceKey, ticket.ownerEpoch) && ticket.operationId >= lastCompletedOperationId) {
                            lastCompletedOperationId = ticket.operationId
                            statusMessage = "Could not delete draft from local storage"
                            validationError = "Could not delete draft from local storage"
                        }
                        return@withLock
                    }

                    if (isCurrentOwner(ticket.deviceKey, ticket.ownerEpoch) && ticket.operationId >= lastCompletedOperationId) {
                        lastCompletedOperationId = ticket.operationId
                        hasPersistedDraft = false
                        persistedInputs = null
                        persistedRevision = -1L
                        recomputeDirtyState()
                        statusMessage = "Saved draft deleted from local storage"
                        validationError = null
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (isCurrentOwner(ticket.deviceKey, ticket.ownerEpoch)) {
                    statusMessage = "Failed to delete saved draft: ${e.message ?: "IO error"}"
                }
            } finally {
                if (acquiredBusyOwnership &&
                    isCurrentOwner(ticket.deviceKey, ticket.ownerEpoch) &&
                    activeSavingOpId == ticket.operationId
                ) {
                    activeSavingOpId = 0L
                    isSaving = false
                }
            }
        }
        activeOperationJob = job
        return job
    }

    /**
     * Re-initializes state when target device ID changes with owner epoch invalidation.
     * Atomically clears transient operation ownership and busy flags, and cooperatively
     * cancels any pending active IO coroutine job.
     */
    fun resetForDevice(
        newDeviceId: String,
        newFallbackLat: Double? = null,
        newFallbackLon: Double? = null
    ) {
        if (deviceId == newDeviceId) return
        activeOperationJob?.cancel()
        activeOperationJob = null
        ownerEpoch++
        deviceId = newDeviceId
        activeSavingOpId = 0L
        activeLoadingOpId = 0L
        isSaving = false
        isLoading = false
        editorRevision = 0L
        persistedRevision = 0L
        persistedInputs = null
        hasPersistedDraft = false
        isDirty = false
        selectedType = "CIRCLE"
        circleLatInput = newFallbackLat?.toString() ?: ""
        circleLonInput = newFallbackLon?.toString() ?: ""
        circleRadiusInput = "150"
        initialInputs = EditorInputs(
            selectedType = "CIRCLE",
            circleLat = newFallbackLat?.toString() ?: "",
            circleLon = newFallbackLon?.toString() ?: "",
            circleRadius = "150",
            polygonVertices = emptyList()
        )
        polygonVertices.clear()
        historyStack.clear()
        lastEditedField = null
        lastEditTimestamp = 0L
        validationError = null
        statusMessage = null
        loadDraft(DraftLoadMode.INITIAL_PRISTINE_FORM)
    }

    /**
     * Serializes holder state into bounded Map for process death restoration.
     * Excludes Context, SharedPreferences, Jobs, Dispatchers, and busy flags.
     */
    fun saveToMap(): Map<String, Any?> = mapOf(
        "deviceKey" to deviceId,
        "ownerEpoch" to ownerEpoch,
        "selectedType" to selectedType,
        "circleLat" to circleLatInput.take(EditorSnapshot.MAX_FIELD_LENGTH),
        "circleLon" to circleLonInput.take(EditorSnapshot.MAX_FIELD_LENGTH),
        "circleRadius" to circleRadiusInput.take(EditorSnapshot.MAX_FIELD_LENGTH),
        "polygonVertices" to polygonVertices.take(EditorSnapshot.MAX_POLYGON_VERTICES).map { "${it.first},${it.second}" },
        "editorRevision" to editorRevision,
        "persistedRevision" to persistedRevision,
        "hasPersistedDraft" to hasPersistedDraft,
        "isDirty" to isDirty,
        "initialInputs_type" to initialInputs.selectedType,
        "initialInputs_lat" to initialInputs.circleLat.take(EditorSnapshot.MAX_FIELD_LENGTH),
        "initialInputs_lon" to initialInputs.circleLon.take(EditorSnapshot.MAX_FIELD_LENGTH),
        "initialInputs_rad" to initialInputs.circleRadius.take(EditorSnapshot.MAX_FIELD_LENGTH),
        "initialInputs_vertices" to initialInputs.polygonVertices.take(EditorSnapshot.MAX_POLYGON_VERTICES).map { "${it.first},${it.second}" },
        "persistedInputs_type" to persistedInputs?.selectedType,
        "persistedInputs_lat" to persistedInputs?.circleLat?.take(EditorSnapshot.MAX_FIELD_LENGTH),
        "persistedInputs_lon" to persistedInputs?.circleLon?.take(EditorSnapshot.MAX_FIELD_LENGTH),
        "persistedInputs_rad" to persistedInputs?.circleRadius?.take(EditorSnapshot.MAX_FIELD_LENGTH),
        "persistedInputs_vertices" to persistedInputs?.polygonVertices?.take(EditorSnapshot.MAX_POLYGON_VERTICES)?.map { "${it.first},${it.second}" },
        "history" to historyStack.takeLast(EditorSnapshot.MAX_HISTORY).map { it.toMap() },
        "needsBaselineReconciliation" to true
    )

    companion object {
        fun restoreFromMap(
            saved: Map<String, Any?>,
            draftStore: GeofenceDraftStore,
            ownerScope: CoroutineScope,
            fallbackLat: Double? = null,
            fallbackLon: Double? = null,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            clockMs: () -> Long = {
                try {
                    android.os.SystemClock.elapsedRealtime()
                } catch (_: Throwable) {
                    System.currentTimeMillis()
                }
            }
        ): GeofenceEditorStateHolder {
            val savedDeviceKey = saved["deviceKey"] as? String ?: ""
            val holder = GeofenceEditorStateHolder(
                initialDeviceId = savedDeviceKey,
                draftStore = draftStore,
                ownerScope = ownerScope,
                fallbackLat = fallbackLat,
                fallbackLon = fallbackLon,
                ioDispatcher = ioDispatcher,
                clockMs = clockMs
            )
            holder.ownerEpoch = (saved["ownerEpoch"] as? Number)?.toLong() ?: 0L
            holder.selectedType = saved["selectedType"] as? String ?: "CIRCLE"
            holder.circleLatInput = (saved["circleLat"] as? String)?.take(EditorSnapshot.MAX_FIELD_LENGTH) ?: ""
            holder.circleLonInput = (saved["circleLon"] as? String)?.take(EditorSnapshot.MAX_FIELD_LENGTH) ?: ""
            holder.circleRadiusInput = (saved["circleRadius"] as? String)?.take(EditorSnapshot.MAX_FIELD_LENGTH) ?: "150"
            (saved["polygonVertices"] as? List<*>)?.take(EditorSnapshot.MAX_POLYGON_VERTICES)?.forEach { item ->
                val parts = (item as? String)?.split(",")
                if (parts?.size == 2) {
                    val lat = parts[0].toDoubleOrNull()
                    val lon = parts[1].toDoubleOrNull()
                    if (lat != null && lon != null) holder.polygonVertices.add(lat to lon)
                }
            }
            holder.editorRevision = (saved["editorRevision"] as? Number)?.toLong() ?: 0L
            holder.persistedRevision = (saved["persistedRevision"] as? Number)?.toLong() ?: 0L
            holder.hasPersistedDraft = saved["hasPersistedDraft"] as? Boolean ?: false

            // Restore initial comparison baseline
            val initType = (saved["initialInputs_type"] as? String) ?: "CIRCLE"
            val initLat = (saved["initialInputs_lat"] as? String)?.take(EditorSnapshot.MAX_FIELD_LENGTH) ?: fallbackLat?.toString() ?: ""
            val initLon = (saved["initialInputs_lon"] as? String)?.take(EditorSnapshot.MAX_FIELD_LENGTH) ?: fallbackLon?.toString() ?: ""
            val initRad = (saved["initialInputs_rad"] as? String)?.take(EditorSnapshot.MAX_FIELD_LENGTH) ?: "150"
            val initVertices = mutableListOf<Pair<Double, Double>>()
            (saved["initialInputs_vertices"] as? List<*>)?.take(EditorSnapshot.MAX_POLYGON_VERTICES)?.forEach { item ->
                val parts = (item as? String)?.split(",")
                if (parts?.size == 2) {
                    val lat = parts[0].toDoubleOrNull()
                    val lon = parts[1].toDoubleOrNull()
                    if (lat != null && lon != null) initVertices.add(lat to lon)
                }
            }
            holder.initialInputs = EditorInputs(initType, initLat, initLon, initRad, initVertices)

            // Restore persisted baseline
            val pType = saved["persistedInputs_type"] as? String
            if (pType != null) {
                val pVertices = mutableListOf<Pair<Double, Double>>()
                (saved["persistedInputs_vertices"] as? List<*>)?.take(EditorSnapshot.MAX_POLYGON_VERTICES)?.forEach { item ->
                    val parts = (item as? String)?.split(",")
                    if (parts?.size == 2) {
                        val lat = parts[0].toDoubleOrNull()
                        val lon = parts[1].toDoubleOrNull()
                        if (lat != null && lon != null) pVertices.add(lat to lon)
                    }
                }
                holder.persistedInputs = EditorInputs(
                    selectedType = pType,
                    circleLat = (saved["persistedInputs_lat"] as? String)?.take(EditorSnapshot.MAX_FIELD_LENGTH) ?: "",
                    circleLon = (saved["persistedInputs_lon"] as? String)?.take(EditorSnapshot.MAX_FIELD_LENGTH) ?: "",
                    circleRadius = (saved["persistedInputs_rad"] as? String)?.take(EditorSnapshot.MAX_FIELD_LENGTH) ?: "150",
                    polygonVertices = pVertices
                )
            }

            // Restore bounded history stack
            holder.historyStack.clear()
            (saved["history"] as? List<*>)?.takeLast(EditorSnapshot.MAX_HISTORY)?.forEach { item ->
                when (item) {
                    is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        EditorSnapshot.fromMap(item as Map<String, Any?>)?.let { holder.historyStack.add(it) }
                    }
                    is String -> {
                        EditorSnapshot.deserialize(item)?.let { holder.historyStack.add(it) }
                    }
                }
            }

            // Reset restored typing-coalescing metadata since clock reference has reset
            holder.lastEditedField = null
            holder.lastEditTimestamp = 0L

            // Recompute truthful dirty state against baseline
            holder.recomputeDirtyState()

            // Busy flags are NOT resumed
            holder.isLoading = false
            holder.isSaving = false

            holder.needsBaselineReconciliation = (saved["needsBaselineReconciliation"] as? Boolean) ?: true

            return holder
        }

        fun saver(
            draftStore: GeofenceDraftStore,
            ownerScope: CoroutineScope,
            fallbackLat: Double? = null,
            fallbackLon: Double? = null,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
            clockMs: () -> Long = {
                try {
                    android.os.SystemClock.elapsedRealtime()
                } catch (_: Throwable) {
                    System.currentTimeMillis()
                }
            }
        ): Saver<GeofenceEditorStateHolder, Map<String, Any?>> = Saver(
            save = { it.saveToMap() },
            restore = { saved -> restoreFromMap(saved, draftStore, ownerScope, fallbackLat, fallbackLon, ioDispatcher, clockMs) }
        )
    }
}
