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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.*
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

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
    RESOLVING,  // Determinate Blue bar
    FINISHED    // Green full bar
}

enum class DnsProtocol {
    DOH, // DNS over HTTPS
    DOT  // DNS over TLS
}

data class AppSettings(
    val server: String = "dns.google",
    val port: Int = 443,
    val protocol: DnsProtocol = DnsProtocol.DOH,
    val ipv4: Boolean = true,
    val ipv6: Boolean = false
)

enum class EditableFile(val fileName: String, val displayName: String) {
    INPUT("input.txt", "Input"),
    OUTPUT("output.txt", "Output"),
    SETTINGS("settings.txt", "Settings")
}

// --- UI Theme ---

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
    
    var totalDomains by remember { mutableIntStateOf(0) }
    var processedCount by remember { mutableIntStateOf(0) }
    
    var job by remember { mutableStateOf<Job?>(null) }
    
    var showInfoDialog by remember { mutableStateOf(false) }
    var editingFile by remember { mutableStateOf<EditableFile?>(null) }
    
    val scope = rememberCoroutineScope()
    val scrollState = rememberLazyListState()
    val context = LocalContext.current

    BackHandler(enabled = editingFile != null) { editingFile = null }
    BackHandler(enabled = showInfoDialog) { showInfoDialog = false }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            scrollState.animateScrollToItem(logs.size - 1)
        }
    }

    suspend fun appendLog(text: String) = withContext(Dispatchers.Main) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logs = logs + "[$time] $text"
    }

    fun clearLog() {
        logs = emptyList()
        processState = ProcessState.IDLE
        totalDomains = 0
        processedCount = 0
    }

    fun stopProcess() {
        if (processState == ProcessState.RESOLVING) {
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            logs = logs + "[$time] Stop requested..."
            job?.cancel()
            processState = ProcessState.IDLE
            val time2 = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            logs = logs + "[$time2] Cancelled by user"
        }
    }

    fun startProcess() {
        if (processState == ProcessState.RESOLVING) return
        
        processState = ProcessState.RESOLVING
        totalDomains = 0
        processedCount = 0
        
        job = scope.launch(Dispatchers.IO) {
            try {
                appendLog("Starting to resolve domains...")
                
                // 1. Read Settings
                appendLog("Reading settings.txt...")
                val settingsFile = File(filesDir, "settings.txt")
                if (!settingsFile.exists()) {
                    settingsFile.writeText("server=dns.google\nport=443\nprotocol=DOH\nipv4=true\nipv6=false")
                }
                val settings = parseSettings(settingsFile)
                appendLog("Server: ${settings.server} (${settings.protocol})")
                appendLog("Port: ${settings.port}")
                appendLog("IPv4: ${settings.ipv4}, IPv6: ${settings.ipv6}")

                // 2. Read Input
                appendLog("Reading input.txt...")
                val inputFile = File(filesDir, "input.txt")
                if (!inputFile.exists()) {
                    val defaultContent = "# Google\ngoogle.com"
                    inputFile.writeText(defaultContent)
                    appendLog("File input.txt created.")
                }

                val inputLines = inputFile.readLines()
                val domainsToProcess = inputLines.filter { it.isNotBlank() && !it.trim().startsWith("#") }
                
                withContext(Dispatchers.Main) {
                    totalDomains = domainsToProcess.size
                    processedCount = 0
                }

                appendLog("Found $totalDomains domains")
                appendLog("----------------------------------------")

                val outputLines = mutableListOf<String>()
                
                // --- PREPARE CLIENTS ---
                
                // For DoH:
                val dohClient = if (settings.protocol == DnsProtocol.DOH) {
                    val bootstrap = OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build()
                    val url = "https://${settings.server}:${settings.port}/dns-query".toHttpUrl()
                    DnsOverHttps.Builder()
                        .client(bootstrap)
                        .url(url)
                        .includeIPv6(settings.ipv6)
                        .build()
                } else null

                // For DoT:
                // Socket factory is obtained when needed

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
                    
                    try {
                        val resolvedIps = mutableListOf<String>()

                        if (settings.protocol == DnsProtocol.DOH) {
                            // --- DoH Strategy ---
                            val addresses = dohClient!!.lookup(domain)
                            addresses.forEach { addr ->
                                val ip = addr.hostAddress ?: ""
                                val isV4 = addr is Inet4Address
                                val isV6 = addr is Inet6Address
                                if ((settings.ipv4 && isV4) || (settings.ipv6 && isV6)) {
                                    resolvedIps.add(ip)
                                }
                            }
                        } else {
                            // --- DoT Strategy ---
                            if (settings.ipv4) {
                                val ips = executeDotQuery(settings, domain, "A")
                                resolvedIps.addAll(ips)
                            }
                            if (settings.ipv6) {
                                val ips = executeDotQuery(settings, domain, "AAAA")
                                resolvedIps.addAll(ips)
                            }
                        }

                        if (resolvedIps.isEmpty()) {
                            appendLog("  No matching records found")
                            outputLines.add("# No records found: $domain")
                        } else {
                            resolvedIps.forEach { ip ->
                                appendLog("  $ip $domain")
                                outputLines.add("$ip $domain")
                            }
                        }

                    } catch (e: Exception) {
                        appendLog("  Lookup failed: ${e.message}")
                        outputLines.add("# Lookup failed: $domain")
                    }

                    withContext(Dispatchers.Main) {
                        processedCount++
                    }
                }

                if (isActive) {
                    appendLog("----------------------------------------")
                    appendLog("Writing output.txt...")
                    val outputFile = File(filesDir, "output.txt")
                    outputFile.writeText(outputLines.joinToString("\n"))
                    appendLog("Success!")
                    
                    withContext(Dispatchers.Main) {
                        processState = ProcessState.FINISHED
                    }
                }

            } catch (e: Exception) {
                appendLog("Error: ${e.message}")
                withContext(Dispatchers.Main) {
                    processState = ProcessState.IDLE
                }
            }
        }
    }

    // --- UI COMPONENTS ---
    
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

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Status", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    val statusText = when(processState) {
                        ProcessState.IDLE -> "Idle"
                        ProcessState.RESOLVING -> "Resolving... ($processedCount/$totalDomains)"
                        ProcessState.FINISHED -> "Done ($totalDomains processed)"
                    }
                    Text(
                        text = statusText,
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
                            val progress = if (totalDomains > 0) processedCount.toFloat() / totalDomains else 0f
                            LinearProgressIndicator(
                                progress = progress,
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
                textContent = "server=dns.google\nport=443\nprotocol=DOH\nipv4=true\nipv6=false"
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
                Text(
                    text = "Editing: ${fileType.displayName}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

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
                                textContent = "server=dns.google\nport=443\nprotocol=DOH\nipv4=true\nipv6=false"
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
    var protocol = DnsProtocol.DOH
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
                    "server" -> server = value
                    "port" -> port = value.toIntOrNull() ?: 443
                    "ipv4" -> ipv4 = value.toBoolean()
                    "ipv6" -> ipv6 = value.toBoolean()
                    "protocol" -> protocol = if (value.equals("DOT", ignoreCase = true)) DnsProtocol.DOT else DnsProtocol.DOH
                }
            }
        }
    }
    return AppSettings(server, port, protocol, ipv4, ipv6)
}

// --- DoT Implementation (Manual Packet Construction) ---

fun executeDotQuery(settings: AppSettings, domain: String, recordType: String): List<String> {
    val typeCode = if (recordType == "AAAA") 28 else 1
    val queryBytes = createDnsQueryPacket(domain, typeCode)
    
    return try {
        val socketFactory = SSLSocketFactory.getDefault()
        val socket = socketFactory.createSocket(settings.server, settings.port) as SSLSocket
        socket.soTimeout = 5000 // 5 sec timeout
        socket.startHandshake()

        val output = DataOutputStream(socket.outputStream)
        val input = DataInputStream(socket.inputStream)

        // DoT uses RFC 7858: 2-byte length prefix + DNS message
        output.writeShort(queryBytes.size)
        output.write(queryBytes)
        output.flush()

        // Read response
        val len = input.readShort().toInt() and 0xFFFF
        val responseBytes = ByteArray(len)
        input.readFully(responseBytes)

        socket.close()
        
        parseDnsResponsePacket(responseBytes, typeCode)
    } catch (e: Exception) {
        // e.printStackTrace()
        emptyList()
    }
}

fun createDnsQueryPacket(domain: String, type: Int): ByteArray {
    val baos = ByteArrayOutputStream()
    val dos = DataOutputStream(baos)

    dos.writeShort(0x1234) // ID
    dos.writeShort(0x0100) // Flags (Recursion Desired)
    dos.writeShort(1)      // QDCOUNT
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
    dos.writeShort(1) // Class IN

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

        // Skip Questions
        for (i in 0 until qdCount) {
            skipName(dis)
            dis.readShort()
            dis.readShort()
        }

        // Parse Answers
        for (i in 0 until anCount) {
            skipName(dis)
            val type = dis.readShort().toInt() and 0xFFFF
            dis.readShort()
            dis.readInt() // TTL
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
