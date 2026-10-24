package com.sentinel.admin.ui.iam

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

data class SovereignIamUiState(
    val isLoading: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val activeTab: Int = 0,
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
        _uiState.update { it.copy(activeTab = tab) }
        if (tab == 1) {
            loadAuditEvents()
        } else {
            loadAdmins()
        }
    }

    fun updateNewAdminId(id: String) {
        _uiState.update { it.copy(newAdminId = id) }
    }

    fun updateNewLabel(label: String) {
        _uiState.update { it.copy(newLabel = label) }
    }

    fun updateNewHours(hours: String) {
        _uiState.update { it.copy(newHours = hours) }
    }

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
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Failed to load admins") }
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
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Failed to load audit ledger") }
            }
        }
    }

    fun triggerPanic() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, statusMessage = "Initiating DEFCON-1 Lockdown...") }
            try {
                val res = deviceApi.triggerPanicLockdown(getBearer())
                val kicked = res.kickedSessions ?: 0
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "DEFCON-1 ACTIVE: All operators suspended ($kicked WebSocket sessions terminated)",
                        mintedToken = null
                    )
                }
                loadData()
            } catch (e: Throwable) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "DEFCON-1 Failed: ${e.message}") }
            }
        }
    }

    fun mintOperator() {
        val id = _uiState.value.newAdminId.trim()
        val label = _uiState.value.newLabel.trim()
        val hours = _uiState.value.newHours.toIntOrNull() ?: 24
        val perms = _uiState.value.selectedScopes.toList()

        if (id.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Admin ID cannot be empty") }
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
                            statusMessage = "Minted $id successfully ($hours hrs)"
                        )
                    }
                    loadAdmins()
                } else {
                    _uiState.update { it.copy(isLoading = false, errorMessage = "Failed to mint operator") }
                }
            } catch (e: Throwable) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Mint error: ${e.message}") }
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
                        statusMessage = "$target status set to $status (kicked: ${res.kickedSessions ?: 0})"
                    )
                }
                loadData()
            } catch (e: Throwable) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Status error: ${e.message}") }
            }
        }
    }

    fun setAdminPermissions(target: String, perms: List<String>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val req = TargetAdminStatusRequestDto(target = target, permissions = perms)
                val res = deviceApi.setAdminPermissions(getBearer(), req)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "$target capability scopes updated"
                    )
                }
                loadData()
            } catch (e: Throwable) {
                _uiState.update { it.copy(isLoading = false, errorMessage = "Permissions error: ${e.message}") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SovereignIamDialog(
    onDismiss: () -> Unit,
    viewModel: SovereignIamViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val clipboardManager = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {},
        containerColor = Color(0xFF0F172A),
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Sovereign IAM & Intrusion Console",
                            color = Color(0xFFF1F5F9),
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color(0xFF94A3B8))
                    }
                }

                // DEFCON-1 Panic Switch Button
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.triggerPanic() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFB91C1C),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "🚨 DEFCON-1 PANIC (LOCK ALL TEMP ADMINS)",
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        letterSpacing = 0.5.sp
                    )
                }

                // Status or Error Banners
                if (uiState.statusMessage != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF064E3B), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = uiState.statusMessage ?: "",
                            color = Color(0xFF34D399),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                if (uiState.errorMessage != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF7F1D1D), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = uiState.errorMessage ?: "",
                            color = Color(0xFFFCA5A5),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Tabs
                Spacer(modifier = Modifier.height(8.dp))
                TabRow(
                    selectedTabIndex = uiState.activeTab,
                    containerColor = Color(0xFF1E293B),
                    contentColor = Color(0xFF38BDF8),
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[uiState.activeTab]),
                            color = Color(0xFF38BDF8)
                        )
                    }
                ) {
                    Tab(
                        selected = uiState.activeTab == 0,
                        onClick = { viewModel.selectTab(0) },
                        text = {
                            Text(
                                "Operators & Scopes",
                                fontWeight = if (uiState.activeTab == 0) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 12.sp
                            )
                        }
                    )
                    Tab(
                        selected = uiState.activeTab == 1,
                        onClick = { viewModel.selectTab(1) },
                        text = {
                            Text(
                                "Intrusion Honeypot",
                                fontWeight = if (uiState.activeTab == 1) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 12.sp
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (uiState.isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color(0xFF38BDF8), modifier = Modifier.size(28.dp))
                    }
                }

                // Tab Content
                when (uiState.activeTab) {
                    0 -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Mint Card
                            item {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text(
                                            "MINT TEMPORARY OPERATOR",
                                            color = Color(0xFF38BDF8),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedTextField(
                                                value = uiState.newAdminId,
                                                onValueChange = { viewModel.updateNewAdminId(it) },
                                                label = { Text("Admin ID", fontSize = 10.sp) },
                                                modifier = Modifier.weight(1f),
                                                singleLine = true,
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = Color(0xFF38BDF8),
                                                    unfocusedBorderColor = Color(0xFF475569),
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White
                                                )
                                            )
                                            OutlinedTextField(
                                                value = uiState.newHours,
                                                onValueChange = { viewModel.updateNewHours(it) },
                                                label = { Text("Hours", fontSize = 10.sp) },
                                                modifier = Modifier.width(70.dp),
                                                singleLine = true,
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = Color(0xFF38BDF8),
                                                    unfocusedBorderColor = Color(0xFF475569),
                                                    focusedTextColor = Color.White,
                                                    unfocusedTextColor = Color.White
                                                )
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        OutlinedTextField(
                                            value = uiState.newLabel,
                                            onValueChange = { viewModel.updateNewLabel(it) },
                                            label = { Text("Label / Deployment Role", fontSize = 10.sp) },
                                            modifier = Modifier.fillMaxWidth(),
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = Color(0xFF38BDF8),
                                                unfocusedBorderColor = Color(0xFF475569),
                                                focusedTextColor = Color.White,
                                                unfocusedTextColor = Color.White
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("Capability Scopes:", color = Color(0xFF94A3B8), fontSize = 10.sp)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            listOf(
                                                "VIEW_TELEMETRY",
                                                "CAMERA_CAPTURE",
                                                "LIVE_AUDIO",
                                                "FILE_ACCESS",
                                                "DEVICE_CONTROL"
                                            ).forEach { scope ->
                                                val isSelected = uiState.selectedScopes.contains(scope)
                                                FilterChip(
                                                    selected = isSelected,
                                                    onClick = { viewModel.toggleScope(scope) },
                                                    label = { Text(scope, fontSize = 9.sp) },
                                                    colors = FilterChipDefaults.filterChipColors(
                                                        selectedContainerColor = Color(0xFF0369A1),
                                                        selectedLabelColor = Color.White,
                                                        containerColor = Color(0xFF0F172A),
                                                        labelColor = Color(0xFF94A3B8)
                                                    )
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Button(
                                            onClick = { viewModel.mintOperator() },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                                            shape = RoundedCornerShape(6.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("Generate Operator Token", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                        }

                                        // Newly minted token card
                                        if (uiState.mintedToken != null) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(Color(0xFF064E3B), RoundedCornerShape(6.dp))
                                                    .padding(8.dp)
                                            ) {
                                                Text(
                                                    "Generated Bearer Token:",
                                                    color = Color(0xFF34D399),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = uiState.mintedToken ?: "",
                                                    color = Color(0xFFE2E8F0),
                                                    fontSize = 9.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    maxLines = 2
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                OutlinedButton(
                                                    onClick = {
                                                        clipboardManager.setText(AnnotatedString(uiState.mintedToken ?: ""))
                                                    },
                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF34D399)),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text("📋 Copy Token to Clipboard", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Active & Registered Admins
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "REGISTERED ADMINISTRATORS (${uiState.admins.size})",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    IconButton(onClick = { viewModel.loadAdmins() }) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }

                            items(uiState.admins) { admin ->
                                val isRoot = admin.role == "SOVEREIGN_ROOT_ADMIN" || admin.adminId == "ADMIN-001"
                                val isRevoked = admin.status == "REVOKED"
                                val isSuspended = admin.status == "SUSPENDED"
                                val isActive = admin.status == "ACTIVE"

                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isRoot) Color(0xFF1E3A8A) else Color(0xFF1E293B)
                                    ),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    admin.adminId,
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .background(
                                                            if (isRoot) Color(0xFF0284C7) else Color(0xFF6B21A8),
                                                            RoundedCornerShape(4.dp)
                                                        )
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        if (isRoot) "SOVEREIGN ROOT" else "OPERATOR",
                                                        color = Color.White,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Black
                                                    )
                                                }
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    modifier = Modifier
                                                        .background(
                                                            when {
                                                                isActive -> Color(0xFF065F46)
                                                                isSuspended -> Color(0xFF92400E)
                                                                else -> Color(0xFF991B1B)
                                                            },
                                                            RoundedCornerShape(4.dp)
                                                        )
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        admin.status,
                                                        color = Color.White,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    if (admin.wsConnected) "🟢 LIVE" else "⚪ OFF",
                                                    color = if (admin.wsConnected) Color(0xFF34D399) else Color(0xFF64748B),
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }

                                        if (admin.label.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(admin.label, color = Color(0xFFCBD5E1), fontSize = 11.sp)
                                        }

                                        Spacer(modifier = Modifier.height(4.dp))
                                        val perms = admin.permissions ?: emptyList()
                                        Text(
                                            "Scopes: " + if (isRoot) "ALL (Sovereign Root)" else if (perms.isEmpty()) "None" else perms.joinToString(", "),
                                            color = Color(0xFF38BDF8),
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace
                                        )

                                        if (!isRoot) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                if (isActive) {
                                                    OutlinedButton(
                                                        onClick = { viewModel.setAdminStatus(admin.adminId, "SUSPENDED") },
                                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFBBF24)),
                                                        modifier = Modifier.weight(1f)
                                                    ) {
                                                        Text("Suspend", fontSize = 10.sp)
                                                    }
                                                } else if (isSuspended || isRevoked) {
                                                    OutlinedButton(
                                                        onClick = { viewModel.setAdminStatus(admin.adminId, "ACTIVE") },
                                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF34D399)),
                                                        modifier = Modifier.weight(1f)
                                                    ) {
                                                        Text("Activate", fontSize = 10.sp)
                                                    }
                                                }
                                                if (!isRevoked) {
                                                    OutlinedButton(
                                                        onClick = { viewModel.setAdminStatus(admin.adminId, "REVOKED") },
                                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444)),
                                                        modifier = Modifier.weight(1f)
                                                    ) {
                                                        Text("Revoke", fontSize = 10.sp)
                                                    }
                                                }
                                                if (isActive) {
                                                    OutlinedButton(
                                                        onClick = {
                                                            viewModel.setAdminPermissions(admin.adminId, listOf("VIEW_TELEMETRY"))
                                                        },
                                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8)),
                                                        modifier = Modifier.weight(1.1f)
                                                    ) {
                                                        Text("Read-Only", fontSize = 9.sp)
                                                    }
                                                    OutlinedButton(
                                                        onClick = {
                                                            viewModel.setAdminPermissions(
                                                                admin.adminId,
                                                                listOf("VIEW_TELEMETRY", "CAMERA_CAPTURE", "LIVE_AUDIO", "FILE_ACCESS", "DEVICE_CONTROL")
                                                            )
                                                        },
                                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF38BDF8)),
                                                        modifier = Modifier.weight(1.1f)
                                                    ) {
                                                        Text("Full Ops", fontSize = 9.sp)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "REAL-TIME INTRUSION HONEYPOT & AUDIT LEDGER (${uiState.auditEvents.size})",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    IconButton(onClick = { viewModel.loadAuditEvents() }) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                                    }
                                }
                            }

                            if (uiState.auditEvents.isEmpty()) {
                                item {
                                    Text(
                                        "No intrusion or command events recorded.",
                                        color = Color(0xFF64748B),
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                            }

                            items(uiState.auditEvents) { ev ->
                                val isCrit = ev.severity.equals("CRITICAL", ignoreCase = true)
                                val isWarn = ev.severity.equals("WARN", ignoreCase = true)

                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(8.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Box(
                                                    modifier = Modifier
                                                        .background(
                                                            when {
                                                                isCrit -> Color(0xFF991B1B)
                                                                isWarn -> Color(0xFF92400E)
                                                                else -> Color(0xFF0369A1)
                                                            },
                                                            RoundedCornerShape(3.dp)
                                                        )
                                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                                ) {
                                                    Text(
                                                        ev.severity,
                                                        color = Color.White,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Black
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    ev.action,
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 11.sp
                                                )
                                            }
                                            Text(
                                                ev.timestamp.take(19).replace("T", " "),
                                                color = Color(0xFF64748B),
                                                fontSize = 9.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            "Actor: ${ev.actorId} | ${ev.detail}",
                                            color = if (isCrit) Color(0xFFFCA5A5) else Color(0xFFCBD5E1),
                                            fontSize = 10.sp
                                        )

                                        if (!ev.remoteIp.isNullOrBlank() || !ev.deviceModel.isNullOrBlank()) {
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                "Origin: ${ev.remoteIp ?: ""} · ${ev.deviceModel ?: ""}",
                                                color = Color(0xFF64748B),
                                                fontSize = 9.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}
