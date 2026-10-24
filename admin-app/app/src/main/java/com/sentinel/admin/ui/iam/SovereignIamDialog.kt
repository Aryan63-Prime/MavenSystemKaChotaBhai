package com.sentinel.admin.ui.iam

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddModerator
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GppBad
import androidx.compose.material.icons.filled.GppGood
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.PhonelinkLock
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sentinel.admin.data.remote.api.AdminAuditEventDto
import com.sentinel.admin.data.remote.api.AdminIamEntryDto
import com.sentinel.admin.data.remote.api.DeviceApi
import com.sentinel.admin.data.remote.api.IssueAdminRequestDto
import com.sentinel.admin.data.remote.api.TargetAdminStatusRequestDto
import com.sentinel.admin.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// Fleet Design System Palette
private val BgObsidian = Color(0xFF070C10)
private val SurfaceCard = Color(0xFF0E161C)
private val SurfaceElevated = Color(0xFF131E26)
private val BorderSubtle = Color(0xFF1B2934)
private val EmeraldPrimary = Color(0xFF10B981)
private val EmeraldSurface = Color(0xFF0A261D)
private val EmeraldBorder = Color(0xFF155E46)
private val AmberWarn = Color(0xFFF59E0B)
private val AmberSurface = Color(0xFF291D0A)
private val CrimsonDanger = Color(0xFFEF4444)
private val CrimsonSurface = Color(0xFF281114)
private val CrimsonBorder = Color(0xFF691E26)
private val TextPrimary = Color(0xFFF1F5F9)
private val TextSecondary = Color(0xFF94A3B8)
private val TextMuted = Color(0xFF64748B)

data class SovereignIamUiState(
    val isLoading: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val activeTab: Int = 0, // 0 = Operators, 1 = Provision, 2 = Audit Log
    val admins: List<AdminIamEntryDto> = emptyList(),
    val auditEvents: List<AdminAuditEventDto> = emptyList(),
    val newAdminId: String = "ADMIN-003",
    val newLabel: String = "Field Operator",
    val newHours: String = "24",
    val selectedScopes: Set<String> = setOf("VIEW_TELEMETRY", "CAMERA_CAPTURE", "LIVE_AUDIO", "FILE_ACCESS", "DEVICE_CONTROL"),
    val mintedToken: String? = null
)

@HiltViewModel
class SovereignIamViewModel @Inject constructor(
    private val deviceApi: DeviceApi,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SovereignIamUiState())
    val uiState: StateFlow<SovereignIamUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun getBearer(): String {
        val t = authRepository.getToken() ?: ""
        return if (t.startsWith("Bearer ")) t else "Bearer $t"
    }

    fun selectTab(tab: Int) {
        _uiState.update { it.copy(activeTab = tab, errorMessage = null, statusMessage = null) }
        when (tab) {
            0 -> loadAdmins()
            2 -> loadAuditEvents()
        }
    }

    fun updateNewAdminId(id: String) = _uiState.update { it.copy(newAdminId = id) }
    fun updateNewLabel(label: String) = _uiState.update { it.copy(newLabel = label) }
    fun updateNewHours(hours: String) = _uiState.update { it.copy(newHours = hours) }

    fun toggleScope(scope: String) {
        _uiState.update { state ->
            val updated = if (state.selectedScopes.contains(scope)) {
                if (scope == "VIEW_TELEMETRY") state.selectedScopes else state.selectedScopes - scope
            } else {
                state.selectedScopes + scope
            }
            state.copy(selectedScopes = updated)
        }
    }

    fun loadData() {
        loadAdmins()
        loadAuditEvents()
    }

    fun loadAdmins() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val res = deviceApi.listAdmins(getBearer())
                _uiState.update { it.copy(isLoading = false, admins = res.admins) }
            } catch (e: Throwable) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Failed to load operators") }
            }
        }
    }

    fun loadAuditEvents() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val res = deviceApi.getAdminAuditEvents(getBearer())
                _uiState.update { it.copy(isLoading = false, auditEvents = res.events) }
            } catch (e: Throwable) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Failed to load audit log") }
            }
        }
    }

    fun triggerPanic() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, statusMessage = null) }
            try {
                val res = deviceApi.triggerPanicLockdown(getBearer())
                val kicked = res.kickedSessions ?: 0
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "Emergency lockdown executed ($kicked active sessions terminated)",
                        mintedToken = null
                    )
                }
                loadData()
            } catch (e: Throwable) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Lockdown failed: ${e.message}") }
            }
        }
    }

    fun mintOperator() {
        val id = _uiState.value.newAdminId.trim()
        val label = _uiState.value.newLabel.trim()
        val hours = _uiState.value.newHours.toIntOrNull() ?: 24
        val perms = _uiState.value.selectedScopes.toList()

        if (id.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Operator ID is required") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, statusMessage = null) }
            try {
                val req = IssueAdminRequestDto(
                    adminId = id,
                    label = label,
                    expiryHours = hours,
                    permissions = perms
                )
                val res = deviceApi.issueTempAdmin(getBearer(), req)
                if (res.success && res.token != null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            mintedToken = res.token,
                            statusMessage = "Provisioned operator $id ($hours hours TTL)"
                        )
                    }
                    loadAdmins()
                } else {
                    _uiState.update { it.copy(isLoading = false, errorMessage = "Unable to provision operator") }
                }
            } catch (e: Throwable) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Provisioning failed") }
            }
        }
    }

    fun setAdminStatus(target: String, status: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val req = TargetAdminStatusRequestDto(target = target, status = status)
                val res = deviceApi.setAdminStatus(getBearer(), req)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "$target transitioned to $status" +
                            (res.kickedSessions?.takeIf { it > 0 }?.let { " ($it sessions disconnected)" } ?: "")
                    )
                }
                loadData()
            } catch (e: Throwable) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Status update failed") }
            }
        }
    }

    fun setAdminPermissions(target: String, perms: List<String>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val req = TargetAdminStatusRequestDto(target = target, permissions = perms)
                deviceApi.setAdminPermissions(getBearer(), req)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "Updated capability scopes for $target"
                    )
                }
                loadData()
            } catch (e: Throwable) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Scope update failed") }
            }
        }
    }
}

private data class ScopeSpec(
    val key: String,
    val label: String,
    val icon: ImageVector
)

private val AllScopes = listOf(
    ScopeSpec("VIEW_TELEMETRY", "Telemetry", Icons.Default.Visibility),
    ScopeSpec("CAMERA_CAPTURE", "Camera", Icons.Default.CameraAlt),
    ScopeSpec("LIVE_AUDIO", "Microphone", Icons.Default.Mic),
    ScopeSpec("FILE_ACCESS", "Files", Icons.Default.Folder),
    ScopeSpec("DEVICE_CONTROL", "Device Control", Icons.Default.PhonelinkLock)
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SovereignIamDialog(
    onDismiss: () -> Unit,
    viewModel: SovereignIamViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val clipboardManager = LocalClipboardManager.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = true
        )
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = BgObsidian,
            topBar = {
                Column(modifier = Modifier.background(BgObsidian)) {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                    tint = TextPrimary
                                )
                            }
                        },
                        title = {
                            Column {
                                Text(
                                    text = "Sovereign Access Matrix",
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                                Text(
                                    text = "Vivo iQOO 13 · SM8750 Silicon Root",
                                    color = EmeraldPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = { viewModel.loadData() }) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh",
                                    tint = TextSecondary
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = BgObsidian,
                            titleContentColor = TextPrimary
                        )
                    )

                    if (uiState.isLoading) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp),
                            color = EmeraldPrimary,
                            trackColor = BorderSubtle
                        )
                    } else {
                        HorizontalDivider(color = BorderSubtle, thickness = 1.dp)
                    }

                    // Clean 3-Tab Navigation Bar
                    TabRow(
                        selectedTabIndex = uiState.activeTab,
                        containerColor = BgObsidian,
                        contentColor = EmeraldPrimary,
                        indicator = { tabPositions ->
                            TabRowDefaults.SecondaryIndicator(
                                Modifier.tabIndicatorOffset(tabPositions[uiState.activeTab]),
                                color = EmeraldPrimary,
                                height = 2.dp
                            )
                        },
                        divider = { HorizontalDivider(color = BorderSubtle, thickness = 1.dp) }
                    ) {
                        Tab(
                            selected = uiState.activeTab == 0,
                            onClick = { viewModel.selectTab(0) },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Operators (${uiState.admins.size})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            },
                            selectedContentColor = EmeraldPrimary,
                            unselectedContentColor = TextSecondary
                        )
                        Tab(
                            selected = uiState.activeTab == 1,
                            onClick = { viewModel.selectTab(1) },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AddModerator, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Provision", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            },
                            selectedContentColor = EmeraldPrimary,
                            unselectedContentColor = TextSecondary
                        )
                        Tab(
                            selected = uiState.activeTab == 2,
                            onClick = { viewModel.selectTab(2) },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Audit (${uiState.auditEvents.size})", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            },
                            selectedContentColor = EmeraldPrimary,
                            unselectedContentColor = TextSecondary
                        )
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
            ) {
                // Status / Error Feedback Banner
                if (uiState.statusMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(EmeraldSurface, RoundedCornerShape(10.dp))
                            .border(1.dp, EmeraldBorder, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(uiState.statusMessage ?: "", color = EmeraldPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }

                if (uiState.errorMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CrimsonSurface, RoundedCornerShape(10.dp))
                            .border(1.dp, CrimsonBorder, RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = CrimsonDanger, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(uiState.errorMessage ?: "", color = CrimsonDanger, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }

                when (uiState.activeTab) {
                    0 -> OperatorsTabContent(
                        admins = uiState.admins,
                        onTriggerPanic = { viewModel.triggerPanic() },
                        onGoToProvision = { viewModel.selectTab(1) },
                        onSetStatus = { id, st -> viewModel.setAdminStatus(id, st) },
                        onSetPermissions = { id, perms -> viewModel.setAdminPermissions(id, perms) }
                    )
                    1 -> ProvisionTabContent(
                        uiState = uiState,
                        onAdminIdChange = { viewModel.updateNewAdminId(it) },
                        onLabelChange = { viewModel.updateNewLabel(it) },
                        onHoursChange = { viewModel.updateNewHours(it) },
                        onToggleScope = { viewModel.toggleScope(it) },
                        onMintClick = { viewModel.mintOperator() },
                        onCopyToken = { tok -> clipboardManager.setText(AnnotatedString(tok)) }
                    )
                    2 -> AuditTabContent(
                        events = uiState.auditEvents
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OperatorsTabContent(
    admins: List<AdminIamEntryDto>,
    onTriggerPanic: () -> Unit,
    onGoToProvision: () -> Unit,
    onSetStatus: (String, String) -> Unit,
    onSetPermissions: (String, List<String>) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Emergency Lockdown Action Bar
        item {
            Surface(
                color = CrimsonSurface,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, CrimsonBorder, RoundedCornerShape(12.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.GppBad,
                            contentDescription = null,
                            tint = CrimsonDanger,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "DEFCON-1 Emergency Lockdown",
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "Suspends all temporary operators & severs live sockets",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onTriggerPanic,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CrimsonDanger,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Lockdown", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Section Header with Provision shortcut
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "REGISTERED IDENTITIES",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                OutlinedButton(
                    onClick = onGoToProvision,
                    shape = RoundedCornerShape(8.dp),
                    border = ButtonDefaults.outlinedButtonBorder(true).copy(brush = SolidColor(EmeraldBorder)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = EmeraldPrimary),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(30.dp)
                ) {
                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("New Operator", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        items(admins, key = { it.adminId }) { admin ->
            val isRoot = admin.role == "SOVEREIGN_ROOT_ADMIN" || admin.adminId == "ADMIN-001"
            val isActive = admin.status == "ACTIVE"
            val isSuspended = admin.status == "SUSPENDED"
            val isRevoked = admin.status == "REVOKED"

            val cardBorder = when {
                isRoot -> EmeraldBorder
                isActive -> BorderSubtle
                isSuspended -> AmberWarn.copy(alpha = 0.4f)
                else -> CrimsonBorder.copy(alpha = 0.5f)
            }

            Surface(
                color = SurfaceCard,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, cardBorder, RoundedCornerShape(14.dp))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    // Top Row: Identity + Role Badge + Status Pill
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(
                                        if (isRoot) EmeraldSurface else SurfaceElevated,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .border(
                                        1.dp,
                                        if (isRoot) EmeraldBorder else BorderSubtle,
                                        RoundedCornerShape(8.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isRoot) Icons.Default.Memory else Icons.Default.Security,
                                    contentDescription = null,
                                    tint = if (isRoot) EmeraldPrimary else TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = admin.adminId,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .background(
                                                if (isRoot) EmeraldSurface else SurfaceElevated,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .border(
                                                1.dp,
                                                if (isRoot) EmeraldBorder else BorderSubtle,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = if (isRoot) "SOVEREIGN ROOT" else "TEMP OPERATOR",
                                            color = if (isRoot) EmeraldPrimary else TextSecondary,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                if (admin.label.isNotBlank()) {
                                    Text(
                                        text = admin.label,
                                        color = TextSecondary,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        // Fleet-Style Status Pill
                        val (pillBg, pillFg) = when {
                            isActive -> EmeraldSurface to EmeraldPrimary
                            isSuspended -> AmberSurface to AmberWarn
                            else -> CrimsonSurface to CrimsonDanger
                        }
                        Row(
                            modifier = Modifier
                                .background(pillBg, RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(pillFg, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = admin.status,
                                color = pillFg,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Telemetry Bar inside Card: Uplink State + Expiry + Scopes
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(BgObsidian, RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .background(
                                            if (admin.wsConnected) EmeraldPrimary else TextMuted,
                                            CircleShape
                                        )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (admin.wsConnected) "SOCKET CONNECTED" else "SOCKET OFFLINE",
                                    color = if (admin.wsConnected) EmeraldPrimary else TextMuted,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            admin.expiresAt?.take(16)?.replace("T", " ")?.let { exp ->
                                Text(
                                    text = "EXP: $exp",
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        val perms = admin.permissions ?: emptyList()
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (isRoot) {
                                AllScopes.forEach { spec ->
                                    ScopeBadge(label = spec.label, icon = spec.icon, active = true)
                                }
                            } else {
                                AllScopes.forEach { spec ->
                                    val granted = perms.any { it.equals(spec.key, ignoreCase = true) || it == "ALL" }
                                    ScopeBadge(label = spec.label, icon = spec.icon, active = granted)
                                }
                            }
                        }
                    }

                    // Operator Action Controls (Hidden for Sovereign Root)
                    if (!isRoot) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (isActive) {
                                OutlinedButton(
                                    onClick = { onSetStatus(admin.adminId, "SUSPENDED") },
                                    shape = RoundedCornerShape(8.dp),
                                    border = ButtonDefaults.outlinedButtonBorder(true).copy(brush = SolidColor(AmberWarn.copy(alpha = 0.5f))),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AmberWarn),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.PauseCircleOutline, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Suspend", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                                OutlinedButton(
                                    onClick = { onSetPermissions(admin.adminId, listOf("VIEW_TELEMETRY")) },
                                    shape = RoundedCornerShape(8.dp),
                                    border = ButtonDefaults.outlinedButtonBorder(true).copy(brush = SolidColor(BorderSubtle)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("View Only", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                                OutlinedButton(
                                    onClick = {
                                        onSetPermissions(
                                            admin.adminId,
                                            listOf("VIEW_TELEMETRY", "CAMERA_CAPTURE", "LIVE_AUDIO", "FILE_ACCESS", "DEVICE_CONTROL")
                                        )
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    border = ButtonDefaults.outlinedButtonBorder(true).copy(brush = SolidColor(EmeraldBorder)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = EmeraldPrimary),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.GppGood, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Full Ops", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            } else if (isSuspended || isRevoked) {
                                OutlinedButton(
                                    onClick = { onSetStatus(admin.adminId, "ACTIVE") },
                                    shape = RoundedCornerShape(8.dp),
                                    border = ButtonDefaults.outlinedButtonBorder(true).copy(brush = SolidColor(EmeraldBorder)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = EmeraldPrimary),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.PlayCircleOutline, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Restore & Activate", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }

                            if (!isRevoked) {
                                OutlinedButton(
                                    onClick = { onSetStatus(admin.adminId, "REVOKED") },
                                    shape = RoundedCornerShape(8.dp),
                                    border = ButtonDefaults.outlinedButtonBorder(true).copy(brush = SolidColor(CrimsonBorder)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CrimsonDanger),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Revoke", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScopeBadge(
    label: String,
    icon: ImageVector,
    active: Boolean
) {
    Row(
        modifier = Modifier
            .background(
                if (active) EmeraldSurface else SurfaceCard,
                RoundedCornerShape(6.dp)
            )
            .border(
                1.dp,
                if (active) EmeraldBorder else BorderSubtle,
                RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) EmeraldPrimary else TextMuted,
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            color = if (active) TextPrimary else TextMuted,
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProvisionTabContent(
    uiState: SovereignIamUiState,
    onAdminIdChange: (String) -> Unit,
    onLabelChange: (String) -> Unit,
    onHoursChange: (String) -> Unit,
    onToggleScope: (String) -> Unit,
    onMintClick: () -> Unit,
    onCopyToken: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Surface(
                color = SurfaceCard,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Key, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Provision Temporary Operator",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Mint a time-bounded JWT credential with hardware-enforced capability scopes.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = uiState.newAdminId,
                            onValueChange = onAdminIdChange,
                            label = { Text("Operator ID", fontSize = 11.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = EmeraldPrimary,
                                unfocusedBorderColor = BorderSubtle,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedLabelColor = EmeraldPrimary,
                                unfocusedLabelColor = TextSecondary
                            )
                        )
                        OutlinedTextField(
                            value = uiState.newHours,
                            onValueChange = onHoursChange,
                            label = { Text("TTL (Hours)", fontSize = 11.sp) },
                            modifier = Modifier.width(110.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = EmeraldPrimary,
                                unfocusedBorderColor = BorderSubtle,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedLabelColor = EmeraldPrimary,
                                unfocusedLabelColor = TextSecondary
                            )
                        )
                    }

                    // Quick Duration Presets
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("6" to "6h", "24" to "24h", "72" to "3d", "168" to "7d").forEach { (hrs, label) ->
                            val selected = uiState.newHours == hrs
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (selected) EmeraldSurface else BgObsidian)
                                    .border(1.dp, if (selected) EmeraldBorder else BorderSubtle, RoundedCornerShape(6.dp))
                                    .clickable { onHoursChange(hrs) }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = label,
                                    color = if (selected) EmeraldPrimary else TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = uiState.newLabel,
                        onValueChange = onLabelChange,
                        label = { Text("Operator Label / Assignment", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = EmeraldPrimary,
                            unfocusedBorderColor = BorderSubtle,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedLabelColor = EmeraldPrimary,
                            unfocusedLabelColor = TextSecondary
                        )
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "AUTHORIZED CAPABILITY SCOPES",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.6.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AllScopes.forEach { spec ->
                            val isSelected = uiState.selectedScopes.contains(spec.key)
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) EmeraldSurface else BgObsidian)
                                    .border(
                                        1.dp,
                                        if (isSelected) EmeraldBorder else BorderSubtle,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { onToggleScope(spec.key) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = spec.icon,
                                    contentDescription = null,
                                    tint = if (isSelected) EmeraldPrimary else TextMuted,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = spec.label,
                                    color = if (isSelected) TextPrimary else TextSecondary,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))
                    Button(
                        onClick = onMintClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldPrimary,
                            contentColor = BgObsidian
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Generate Operator Token",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        if (uiState.mintedToken != null) {
            item {
                Surface(
                    color = EmeraldSurface,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, EmeraldBorder, RoundedCornerShape(14.dp))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldPrimary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "SIGNED OPERATOR BEARER TOKEN",
                                color = EmeraldPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(BgObsidian, RoundedCornerShape(8.dp))
                                .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = uiState.mintedToken ?: "",
                                color = TextPrimary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { onCopyToken(uiState.mintedToken ?: "") },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldPrimary,
                                contentColor = BgObsidian
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Copy Token to Clipboard", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditTabContent(
    events: List<AdminAuditEventDto>
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (events.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No intrusion or audit events recorded.", color = TextMuted, fontSize = 13.sp)
                }
            }
        }

        items(events) { ev ->
            val isCrit = ev.severity.equals("CRITICAL", ignoreCase = true)
            val isWarn = ev.severity.equals("WARN", ignoreCase = true)

            val (badgeBg, badgeFg, icon) = when {
                isCrit -> Triple(CrimsonSurface, CrimsonDanger, Icons.Default.GppBad)
                isWarn -> Triple(AmberSurface, AmberWarn, Icons.Default.WarningAmber)
                else -> Triple(EmeraldSurface, EmeraldPrimary, Icons.Default.Info)
            }

            Surface(
                color = SurfaceCard,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (isCrit) CrimsonBorder else BorderSubtle,
                        RoundedCornerShape(12.dp)
                    )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .background(badgeBg, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(icon, contentDescription = null, tint = badgeFg, modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = ev.severity,
                                        color = badgeFg,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = ev.action,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                        Text(
                            text = ev.timestamp.take(19).replace("T", " "),
                            color = TextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = ev.detail,
                        color = if (isCrit) Color(0xFFFCA5A5) else TextSecondary,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "ACTOR: ${ev.actorId}",
                            color = EmeraldPrimary,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (!ev.remoteIp.isNullOrBlank() || !ev.deviceModel.isNullOrBlank()) {
                            Text(
                                text = listOfNotNull(
                                    ev.remoteIp?.takeIf { it.isNotBlank() },
                                    ev.deviceModel?.takeIf { it.isNotBlank() }
                                ).joinToString(" · "),
                                color = TextMuted,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}
