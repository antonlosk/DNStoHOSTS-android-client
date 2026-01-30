package com.dnstohosts.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.*
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DNStoHOSTSTheme(darkTheme = true) {
                val filesDir = getExternalFilesDir(null) ?: filesDir
                MainScreen(filesDir = filesDir)
            }
        }
    }
}

// --- Data Models ---

enum class ProcessState {
    IDLE,       // Grey bar
    RESOLVING,  // Blue indeterminate
    FINISHED    // Green full
}

data class AppSettings(
    val server: String = "dns.google",
    val port: Int = 443,
    val ipv4: Boolean = true,
    val ipv6: Boolean = false
)

enum class EditableFile(val fileName: String, val displayName: String) {
    INPUT("input.txt", "Input"),
    OUTPUT("output.txt", "Output"),
    SETTINGS("settings.txt", "Settings")
}

// --- UI Theme (Material You / Dynamic Colors) ---

@Composable
fun DNStoHOSTSTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> DarkColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF82B1FF),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF2C2C2E),
    onPrimaryContainer = Color(0xFF82B1FF),
    background = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFE5E5E5),
    surfaceVariant = Color(0xFF2C2C2E),
    error = Color(0xFFCF6679)
)

// --- Main Screen ---

@Composable
fun MainScreen(filesDir: File) {
    var logs by remember { mutableStateOf(listOf<String>()) }
    var processState by remember { mutableStateOf(ProcessState.IDLE) }
    var job by remember { mutableStateOf<Job?>(null) }
    
    // Dialog States
    var showInfoDialog by remember { mutableStateOf(false) }
    var editingFile by remember { mutableStateOf<EditableFile?>(null) }
    
    val scope = rememberCoroutineScope()
    val scrollState = rememberLazyListState()
    val context = LocalContext.current

    // Predictive Back Handlers
    BackHandler(enabled = editingFile != null) {
        editingFile = null
    }

    BackHandler(enabled = showInfoDialog) {
        showInfoDialog = false
    }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            scrollState.animateScrollToItem(logs.size - 1)
        }
    }

    fun appendLog(text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logs = logs + "[$time] $text"
    }

    fun clearLog() {
        logs = emptyList()
        processState = ProcessState.IDLE
    }

    fun stopProcess() {
        if (processState == ProcessState.RESOLVING) {
            appendLog("Stop requested, waiting for current operation to complete...")
            job?.cancel()
            processState = ProcessState.IDLE
            appendLog("Operation cancelled by user")
        }
    }

    fun startProcess() {
        if (processState == ProcessState.RESOLVING) return
        
        processState = ProcessState.RESOLVING
        
        job = scope.launch(Dispatchers.IO) {
            try {
                appendLog("Starting to resolve domains...")
                
                // 1. Read Settings
                appendLog("Reading settings.txt...")
                val settingsFile = File(filesDir, "settings.txt")
                if (!settingsFile.exists()) {
                    settingsFile.writeText("server=dns.google\nport=443\nipv4=true\nipv6=false")
                }
                val settings = parseSettings(settingsFile)
                appendLog("DNS Server: ${settings.server}")
                appendLog("IPv4: ${settings.ipv4}, IPv6: ${settings.ipv6}")

                // 2. Read Input
                appendLog("Reading input.txt...")
                val inputFile = File(filesDir, "input.txt")
                if (!inputFile.exists()) {
                    val defaultContent = "# Google\ngoogle.com"
                    inputFile.writeText(defaultContent)
                    appendLog("File input.txt created with default content.")
                }

                val inputLines = inputFile.readLines()
                val domainsToProcess = inputLines.filter { it.isNotBlank() && !it.trim().startsWith("#") }
                appendLog("Found ${domainsToProcess.size} domains to resolve")
                appendLog("----------------------------------------")

                val outputLines = mutableListOf<String>()
                
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()

                for (line in inputLines) {
                    if (!isActive) break

                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) {
                        outputLines.add("")
                        continue
                    }

                    if (trimmed.startsWith("#")) {
                        appendLog(trimmed)
                        outputLines.add(trimmed)
                        continue
                    }

                    val domain = trimmed
                    appendLog("Resolving: $domain")
                    
                    val resolvedIps = mutableListOf<String>()

                    if (settings.ipv4) {
                        val ips = executeBinaryDnsQuery(client, settings, domain, "A")
                        resolvedIps.addAll(ips)
                    }
                    if (settings.ipv6) {
                        val ips = executeBinaryDnsQuery(client, settings, domain, "AAAA")
                        resolvedIps.addAll(ips)
                    }

                    if (resolvedIps.isEmpty()) {
                        appendLog("  No records found for $domain")
                        outputLines.add("# No records found: $domain")
                    } else {
                        resolvedIps.forEach { ip ->
                            appendLog("  $ip $domain")
                            outputLines.add("$ip $domain")
                        }
                    }
                }

                if (isActive) {
                    appendLog("----------------------------------------")
                    appendLog("Writing output.txt...")
                    val outputFile = File(filesDir, "output.txt")
                    outputFile.writeText(outputLines.joinToString("\n"))
                    appendLog("Successfully wrote ${outputLines.size} lines to output.txt")
                    processState = ProcessState.FINISHED
                }

            } catch (e: CancellationException) {
                // Handled
            } catch (e: Exception) {
                appendLog("Error: ${e.message}")
            }
        }
    }

    // --- DIALOGS ---
    
    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Source Code", color = MaterialTheme.colorScheme.onSurface) },
            text = {
                Column {
                    Text("Project Repository:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "https://github.com/antonlosk/\nDNStoHOSTS-android-client",
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/antonlosk/DNStoHOSTS-android-client"))
                            context.startActivity(intent)
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoDialog = false }) {
                    Text("Close", color = MaterialTheme.colorScheme.primary)
                }
            }
        )
    }

    // --- FILE EDITOR ---
    editingFile?.let { fileType ->
        FileEditorDialog(
            fileType = fileType,
            filesDir = filesDir,
            onDismiss = { editingFile = null }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // --- Top Bar ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "DNStoHOSTS",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    )
                    Text(
                        text = "by antonlosk",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
                
                IconButton(onClick = { showInfoDialog = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "About",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // --- Control Buttons ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { startProcess() },
                    enabled = processState != ProcessState.RESOLVING,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Text("Start", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
                
                Button(
                    onClick = { stopProcess() },
                    enabled = processState == ProcessState.RESOLVING,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.error,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Text("Stop", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }

                Button(
                    onClick = { clearLog() },
                    enabled = processState != ProcessState.RESOLVING,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Text("Clear", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // --- Log Area ---
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(vertical = 8.dp, horizontal = 16.dp)
                    ) {
                        Text(
                            text = "Execution Log",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    LazyColumn(
                        state = scrollState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    ) {
                        items(logs) { logLine ->
                            Text(
                                text = logLine,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- File Edit Buttons ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val buttonColors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val buttonModifier = Modifier.weight(1f).height(40.dp)
                val buttonShape = RoundedCornerShape(8.dp)

                Button(
                    onClick = { editingFile = EditableFile.INPUT },
                    enabled = processState != ProcessState.RESOLVING,
                    modifier = buttonModifier,
                    shape = buttonShape,
                    colors = buttonColors,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("Input", fontSize = 13.sp)
                }

                Button(
                    onClick = { editingFile = EditableFile.OUTPUT },
                    enabled = processState != ProcessState.RESOLVING,
                    modifier = buttonModifier,
                    shape = buttonShape,
                    colors = buttonColors,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("Output", fontSize = 13.sp)
                }

                Button(
                    onClick = { editingFile = EditableFile.SETTINGS },
                    enabled = processState != ProcessState.RESOLVING,
                    modifier = buttonModifier,
                    shape = buttonShape,
                    colors = buttonColors,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("Settings", fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // --- Progress Bar ---
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Status", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Text(
                        text = when(processState) {
                            ProcessState.IDLE -> "Idle"
                            ProcessState.RESOLVING -> "Resolving..."
                            ProcessState.FINISHED -> "Done"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))
                        .clip(RoundedCornerShape(3.dp))
                ) {
                    when (processState) {
                        ProcessState.IDLE -> {}
                        ProcessState.RESOLVING -> {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                        ProcessState.FINISHED -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.primary) 
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- File Editor Component ---

@Composable
fun FileEditorDialog(
    fileType: EditableFile,
    filesDir: File,
    onDismiss: () -> Unit
) {
    val file = File(filesDir, fileType.fileName)
    var textContent by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    LaunchedEffect(fileType) {
        if (!file.exists()) {
            if (fileType == EditableFile.INPUT) {
                textContent = "# Google\ngoogle.com"
            } else if (fileType == EditableFile.SETTINGS) {
                // Default with 'server'
                textContent = "server=dns.google\nport=443\nipv4=true\nipv6=false"
            } else {
                file.createNewFile()
                textContent = ""
            }
        } else {
            textContent = file.readText()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                // Header
                Text(
                    text = "Editing: ${fileType.displayName}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Editor Area
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    BasicTextField(
                        value = textContent,
                        onValueChange = { textContent = it },
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Buttons Grid
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        onClick = {
                            file.writeText(textContent)
                            Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Save", color = MaterialTheme.colorScheme.onPrimary)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(textContent))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Text("Copy", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    }

                    Button(
                        onClick = { textContent = "" },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Text("Clear", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }

                    if (fileType == EditableFile.SETTINGS) {
                        Button(
                            onClick = {
                                // Reset with 'server'
                                textContent = "server=dns.google\nport=443\nipv4=true\nipv6=false"
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Text("Reset", color = Color(0xFFFFD60A), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// --- Logic Implementation ---

fun parseSettings(file: File): AppSettings {
    var server = "dns.google"
    var port = 443
    var ipv4 = true
    var ipv6 = false

    file.forEachLine { line ->
        val trimmed = line.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
            val parts = trimmed.split("=", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim().lowercase()
                val value = parts[1].trim()
                
                when (key) {
                    "server" -> server = value // ONLY accept 'server'
                    "port" -> port = value.toIntOrNull() ?: 443
                    "ipv4" -> ipv4 = value.toBoolean()
                    "ipv6" -> ipv6 = value.toBoolean()
                }
            }
        }
    }
    return AppSettings(server, port, ipv4, ipv6)
}

// --- Binary DNS Implementation ---

fun executeBinaryDnsQuery(client: OkHttpClient, settings: AppSettings, domain: String, recordType: String): List<String> {
    val qType = if (recordType == "AAAA") 28 else 1
    val queryBytes = createDnsQueryPacket(domain, qType)
    val url = "https://${settings.server}:${settings.port}/dns-query"
    
    val requestBody = queryBytes.toRequestBody("application/dns-message".toMediaType())
    
    val request = Request.Builder()
        .url(url)
        .addHeader("Accept", "application/dns-message")
        .post(requestBody)
        .build()

    return try {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val bodyBytes = response.body?.bytes() ?: return emptyList()
            parseDnsResponsePacket(bodyBytes, qType)
        }
    } catch (e: Exception) {
        emptyList()
    }
}

fun createDnsQueryPacket(domain: String, type: Int): ByteArray {
    val baos = ByteArrayOutputStream()
    val dos = DataOutputStream(baos)

    dos.writeShort(0x1234)
    dos.writeShort(0x0100)
    dos.writeShort(1)
    dos.writeShort(0)
    dos.writeShort(0)
    dos.writeShort(0)

    for (label in domain.split(".")) {
        val bytes = label.toByteArray(Charsets.UTF_8)
        dos.writeByte(bytes.size)
        dos.write(bytes)
    }
    dos.writeByte(0)

    dos.writeShort(type)
    dos.writeShort(1)

    return baos.toByteArray()
}

fun parseDnsResponsePacket(data: ByteArray, reqType: Int): List<String> {
    val results = mutableListOf<String>()
    val dis = DataInputStream(ByteArrayInputStream(data))

    try {
        dis.readShort()
        dis.readShort()
        val qdCount = dis.readShort()
        val anCount = dis.readShort()
        dis.readShort()
        dis.readShort()

        for (i in 0 until qdCount) {
            skipName(dis)
            dis.readShort()
            dis.readShort()
        }

        for (i in 0 until anCount) {
            skipName(dis)
            val type = dis.readShort().toInt() and 0xFFFF
            dis.readShort()
            dis.readInt()
            val rdLength = dis.readShort().toInt() and 0xFFFF

            val rData = ByteArray(rdLength)
            dis.readFully(rData)

            if (type == reqType) {
                try {
                    val inetAddress = InetAddress.getByAddress(rData)
                    results.add(inetAddress.hostAddress ?: "")
                } catch (e: Exception) {}
            }
        }
    } catch (e: EOFException) {} catch (e: Exception) {}

    return results
}

fun skipName(dis: DataInputStream) {
    while (true) {
        dis.mark(1)
        val len = dis.readByte().toInt() and 0xFF
        if (len == 0) return
        if ((len and 0xC0) == 0xC0) {
            dis.readByte() 
            return
        }
        dis.skipBytes(len)
    }
}
