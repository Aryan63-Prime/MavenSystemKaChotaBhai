package com.sentinel.admin.ui.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sentinel.admin.domain.model.ConnectionState

/**
 * Login screen composable.
 *
 * Stateless — all state comes from [LoginUiState].
 * Events dispatched via lambda callbacks.
 */
@Composable
fun LoginScreen(
    uiState: LoginUiState,
    onServerUrlChanged: (String) -> Unit,
    onTokenChanged: (String) -> Unit,
    onRememberMeChanged: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    var tokenVisible by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0B0F14),
                        Color(0xFF151B22)
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // ---- Header ----
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFFAAC7E8).copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(27.dp),
                    tint = Color(0xFFAAC7E8)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "SENTINEL",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                color = Color(0xFFF0F2F4)
            )

            Text(
                text = "DEVICE FLEET MANAGEMENT",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                color = Color(0xFF8FB2D8)
            )

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Your fleet, in one secure place.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF9AA7B6),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(28.dp))

            // ---- Connection Status Banner ----
            ConnectionStatusBanner(uiState = uiState)

            Spacer(modifier = Modifier.height(20.dp))

            // ---- Login Card ----
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF151B22)
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF293542)),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                .padding(18.dp)
            ) {
                    Text(
                        text = "Connect to your command center",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF0F2F4)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Enter your server address and administrator token.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9AA7B6)
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    // Server URL
                    OutlinedTextField(
                        value = uiState.serverUrl,
                        onValueChange = onServerUrlChanged,
                        label = { Text("Server address") },
                        placeholder = { Text("wss://sentinel.example.com/ws", color = Color(0xFF7D8997)) },
                        isError = uiState.serverUrlError != null,
                        supportingText = uiState.serverUrlError?.let { { Text(it) } },
                        enabled = uiState.inputsEnabled,
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF0B0F14),
                            unfocusedContainerColor = Color(0xFF0B0F14),
                            focusedBorderColor = Color(0xFFAAC7E8),
                            unfocusedBorderColor = Color(0xFF293542),
                            focusedTextColor = Color(0xFFF0F2F4),
                            unfocusedTextColor = Color(0xFFF0F2F4),
                            focusedLabelColor = Color(0xFFAAC7E8),
                            unfocusedLabelColor = Color(0xFF9AA7B6)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Next
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // JWT Token
                    OutlinedTextField(
                        value = uiState.token,
                        onValueChange = onTokenChanged,
                        label = { Text("Administrator token") },
                        placeholder = { Text("Admin Bearer Token…", color = Color(0xFF7D8997)) },
                        isError = uiState.tokenError != null,
                        supportingText = uiState.tokenError?.let { { Text(it) } },
                        enabled = uiState.inputsEnabled,
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF0B0F14),
                            unfocusedContainerColor = Color(0xFF0B0F14),
                            focusedBorderColor = Color(0xFFAAC7E8),
                            unfocusedBorderColor = Color(0xFF293542),
                            focusedTextColor = Color(0xFFF0F2F4),
                            unfocusedTextColor = Color(0xFFF0F2F4),
                            focusedLabelColor = Color(0xFFAAC7E8),
                            unfocusedLabelColor = Color(0xFF9AA7B6)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        visualTransformation = if (tokenVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            IconButton(onClick = { tokenVisible = !tokenVisible }) {
                                Icon(
                                    imageVector = if (tokenVisible) {
                                        Icons.Default.VisibilityOff
                                    } else {
                                        Icons.Default.Visibility
                                    },
                                    tint = Color(0xFF9AA7B6),
                                    contentDescription = if (tokenVisible) {
                                        "Hide token"
                                    } else {
                                        "Show token"
                                    }
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Remember Me
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = uiState.rememberMe,
                            onCheckedChange = onRememberMeChanged,
                            enabled = uiState.inputsEnabled,
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFFAAC7E8),
                                checkmarkColor = Color(0xFF0B0F14),
                                uncheckedColor = Color(0xFF7D8997)
                            )
                        )
                        Text(
                            text = "Remember this device",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF9AA7B6)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (uiState.canDisconnect) {
                            OutlinedButton(
                                onClick = onDisconnect,
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF293542)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color(0xFFF0F2F4)
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Disconnect", fontWeight = FontWeight.Bold)
                            }
                        }

                        Button(
                            onClick = onConnect,
                            enabled = uiState.canConnect,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFAAC7E8),
                                contentColor = Color(0xFF0B0F14)
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (uiState.isConnecting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF0B0F14)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                if (uiState.isConnecting) "Connecting…" else "Connect",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // ---- Error Message ----
            AnimatedVisibility(
                visible = uiState.errorMessage != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                uiState.errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                            .padding(12.dp)
                    )
                }
            }
        }
    }
}

/**
 * Animated connection status banner at the top of the login form.
 */
@Composable
private fun ConnectionStatusBanner(uiState: LoginUiState) {
    val backgroundColor by animateColorAsState(
        targetValue = when (uiState.connectionState) {
            is ConnectionState.Ready -> MaterialTheme.colorScheme.primaryContainer
            is ConnectionState.Error -> MaterialTheme.colorScheme.errorContainer
            is ConnectionState.Reconnecting -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "statusBg"
    )

    val textColor by animateColorAsState(
        targetValue = when (uiState.connectionState) {
            is ConnectionState.Ready -> MaterialTheme.colorScheme.onPrimaryContainer
            is ConnectionState.Error -> MaterialTheme.colorScheme.onErrorContainer
            is ConnectionState.Reconnecting -> MaterialTheme.colorScheme.onTertiaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "statusText"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (uiState.isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = textColor
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = uiState.statusText,
                style = MaterialTheme.typography.labelLarge,
                color = textColor
            )
        }
    }
}
