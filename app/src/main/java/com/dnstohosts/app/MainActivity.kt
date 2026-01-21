package com.dnstohosts.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
            DNStoHOSTSTheme {
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
    val address: String = "dns.google",
    val port: Int = 443,
    val ipv4: Boolean = true,
    val ipv6: Boolean = false
)

// --- UI Theme (Android 16 / WireGuard Dark Style) ---

@Composable
fun DNStoHOSTSTheme(content: @Composable () -> Unit) {
    // Forced Dark Theme Colors
    val darkColors = darkColorScheme(
        primary = Color(0xFF82B1FF),    // Светло-синий акцент
        onPrimary = Color.Black,
        background = Color(0xFF000000), // Абсолютно черный фон (WireGuard style)
        surface = Color(0xFF1C1C1E),    // Темно-серые карточки (iOS/Android 16 style)
        onSurface = Color(0xFFE5E5E5),  // Белый текст
        surfaceVariant = Color(0xFF2C2C2E),
        error = Color(0xFFCF6679)
    )

    MaterialTheme(
        colorScheme = darkColors,
        typography = Typography(),
        content = content
    )
}

// --- Main Screen ---

@Composable
fun MainScreen(filesDir: File) {
    var logs by remember { mutableStateOf(listOf<String>()) }
    var processState by remember { mutableStateOf(ProcessState.IDLE) }
    var job by remember { mutableStateOf<Job?>(null) }
    
    // State for GitHub Dialog
    var showInfoDialog by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    val scrollState = rememberLazyListState()
    val context = LocalContext.current

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
                    settingsFile.writeText("adress=dns.google\nport=443\nipv4=true\nipv6=false")
                }
                val settings = parseSettings(settingsFile)
                appendLog("DNS Server: ${settings.address}")
                appendLog("IPv4: ${settings.ipv4}, IPv6: ${settings.ipv6}")

                // 2. Read Input
                appendLog("Reading input.txt...")
                val inputFile = File(filesDir, "input.txt")
                if (!inputFile.exists()) {
                    inputFile.createNewFile()
                    appendLog("File input.txt not found, created empty file.")
                    processState = ProcessState.FINISHED
                    return@launch
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
                // Keep state as is or reset
            }
        }
    }

    // --- DIALOG ---
    if (showInfoDialog) {
        AlertDialog(
            onDismissRequest = { showInfoDialog = false },
            containerColor = Color(0xFF1C1C1E), // Match card style
            title = {
                Text("Source Code", color = Color.White)
            },
            text = {
                Column {
                    Text("Project Repository:", color = Color.Gray, fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "https://github.com/antonlosk/\nDNStoHOSTS-android-client",
                        color = Color(0xFF82B1FF), // Link Blue
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
                    Text("Close", color = Color(0xFF82B1FF))
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background // Black
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            // --- Top Bar (Title + Info Button) ---
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
                            color = Color.White
                        )
                    )
                    Text(
                        text = "by antonlosk",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Normal,
                            color = Color.Gray
                        )
                    )
                }
                
                // Info Button
                IconButton(onClick = { showInfoDialog = true }) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "About",
                        tint = Color.Gray,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // --- Control Buttons ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Start Button
                Button(
                    onClick = { startProcess() },
                    enabled = processState != ProcessState.RESOLVING,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2C2C2E), // Dark Grey button
                        contentColor = Color(0xFF82B1FF),   // Blue text
                        disabledContainerColor = Color(0xFF1C1C1E),
                        disabledContentColor = Color.Gray
                    )
                ) {
                    Text("Start", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
                
                // Stop Button
                Button(
                    onClick = { stopProcess() },
                    enabled = processState == ProcessState.RESOLVING,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2C2C2E),
                        contentColor = Color(0xFFFF453A),   // Red text
                        disabledContainerColor = Color(0xFF1C1C1E),
                        disabledContentColor = Color.Gray
                    )
                ) {
                    Text("Stop", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }

                // Clear Log
                Button(
                    onClick = { clearLog() },
                    enabled = processState != ProcessState.RESOLVING,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2C2C2E),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFF1C1C1E),
                        disabledContentColor = Color.Gray
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
                color = Color(0xFF1C1C1E), // Card background
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Header for Log
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF2C2C2E))
                            .padding(vertical = 8.dp, horizontal = 16.dp)
                    ) {
                        Text(
                            text = "Execution Log",
                            color = Color.LightGray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    // List
                    LazyColumn(
                        state = scrollState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                    ) {
                        items(logs) { logLine ->
                            Text(
                                text = logLine,
                                color = Color(0xFFD0D0D0),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // --- Progress Bar ---
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Status", color = Color.Gray, fontSize = 12.sp)
                    Text(
                        text = when(processState) {
                            ProcessState.IDLE -> "Idle"
                            ProcessState.RESOLVING -> "Resolving..."
                            ProcessState.FINISHED -> "Done"
                        },
                        color = Color.Gray, 
                        fontSize = 12.sp
                    )
                }
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(Color(0xFF2C2C2E), RoundedCornerShape(3.dp))
                        .clip(RoundedCornerShape(3.dp))
                ) {
                    when (processState) {
                        ProcessState.IDLE -> {
                            // Empty/Grey
                        }
                        ProcessState.RESOLVING -> {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxSize(),
                                color = Color(0xFF0A84FF), // iOS Blue
                                trackColor = Color(0xFF2C2C2E)
                            )
                        }
                        ProcessState.FINISHED -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF32D74B)) // iOS Green
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- Logic Implementation ---

fun parseSettings(file: File): AppSettings {
    var address = "dns.google"
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
                    "adress", "address" -> address = value
                    "port" -> port = value.toIntOrNull() ?: 443
                    "ipv4" -> ipv4 = value.toBoolean()
                    "ipv6" -> ipv6 = value.toBoolean()
                }
            }
        }
    }
    return AppSettings(address, port, ipv4, ipv6)
}

// --- Binary DNS Implementation ---

fun executeBinaryDnsQuery(client: OkHttpClient, settings: AppSettings, domain: String, recordType: String): List<String> {
    val qType = if (recordType == "AAAA") 28 else 1
    val queryBytes = createDnsQueryPacket(domain, qType)
    val url = "https://${settings.address}:${settings.port}/dns-query"
    
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
        dis.readShort() // ID
        dis.readShort() // Flags
        val qdCount = dis.readShort()
        val anCount = dis.readShort()
        dis.readShort() // nsCount
        dis.readShort() // arCount

        for (i in 0 until qdCount) {
            skipName(dis)
            dis.readShort() // QTYPE
            dis.readShort() // QCLASS
        }

        for (i in 0 until anCount) {
            skipName(dis)
            val type = dis.readShort().toInt() and 0xFFFF
            dis.readShort() // Class
            dis.readInt()   // TTL
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
