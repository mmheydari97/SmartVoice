package com.example.smartvoice

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

class MainActivity : ComponentActivity() {

    private var audioRecord: AudioRecord? = null
    private var isRecording by mutableStateOf(false)
    private var isProcessing by mutableStateOf(false)
    private val client = OkHttpClient()

    private val textHistory = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestPermissions()

        setContent {
            MainScreen()
        }
    }

    @Composable
    fun MainScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Scrollable History of Received Text Responses
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                items(textHistory) { text ->
                    MessageBubble(text)
                }
            }

            // Recording Button at the Bottom
            Button(
                onClick = {
                    if (!isRecording) {
                        startRecording()
                    } else {
                        stopRecording()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                enabled = !isProcessing // Disable button while processing
            ) {
                Text(if (isRecording) "Stop & Send" else "Start Recording")
            }
        }
    }

    @Composable
    fun MessageBubble(message: String) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .background(color = Color.LightGray, shape = MaterialTheme.shapes.medium)
                .padding(12.dp)
        ) {
            Text(
                text = message,
                fontSize = 16.sp,
                textAlign = TextAlign.Start
            )
        }
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET
        )

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions, 200)
        }
    }

    private fun startRecording() {
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        // Apply noise suppression if available
        audioRecord?.audioSessionId?.let { sessionId ->
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)
            } else {
                Toast.makeText(this, "Noise suppression not available", Toast.LENGTH_SHORT).show()
            }
        }

        audioRecord?.startRecording()
        isRecording = true

        // Buffer to store PCM data
        val outputStream = ByteArrayOutputStream()

        // Start a thread to record and send audio data
        Thread {
            try {
                val buffer = ByteArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        outputStream.write(buffer, 0, read)
                    }
                }
                Log.d("app_flow", "Recording finished!")

                sendAudioToServer(outputStream.toByteArray())

            } catch (e: IOException) {
                Log.d("app_flow", "Recording failed!")
                e.printStackTrace()
            }
        }.start()
    }

    private fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun sendAudioToServer(pcmData: ByteArray) {
        isProcessing = true
        val url = "https://8000--main--kian-ws--kian--o1bn8ir3s2nsq.pit-1.try.coder.app/upload-audio/"

        // Create request body
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "audio.pcm",
                pcmData.toRequestBody("audio/x-pcm".toMediaTypeOrNull(), 0)
            )
            .build()

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("app_flow", "Failed to send audio: ${e.message}")
                runOnUiThread {
                    Toast.makeText(applicationContext, "Failed to send audio", Toast.LENGTH_SHORT).show()
                    isProcessing = false
                }
            }

// If there's audio response in Wav format
//            override fun onResponse(call: Call, response: Response) {
//                if (!response.isSuccessful) {
//                    Log.e("app_flow", "Server returned error: ${response.code}")
//                    return
//                }
//
//                val responseBody = response.body?.bytes()
//                if (responseBody != null) {
//                    saveReceivedWavFile(responseBody)
//                }
//            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    Log.e("app_flow", "Server returned error: ${response.code}")
                    return
                }

                response.body?.string()?.let {
                    val responseJson = JSONObject(it)
                    val text = responseJson.getString("text")
                    Log.d("app_flow", "Received text: $text")

                    runOnUiThread {
                        textHistory.add(text) // Add response to history
                        isProcessing = false
                    }
                }
            }
        })
    }

// Save received WAV file
//    private fun saveReceivedWavFile(wavData: ByteArray) {
//        val outputFile = File(getExternalFilesDir(null), "converted_audio.wav")
//        try {
//            outputFile.writeBytes(wavData)
//            Log.d("app_flow", "WAV file saved: ${outputFile.absolutePath}")
//            runOnUiThread {
//                Toast.makeText(applicationContext, "Received and saved WAV file", Toast.LENGTH_SHORT).show()
//            }
//        } catch (e: IOException) {
//            Log.e("app_flow", "Failed to save WAV file: ${e.message}")
//        }
//    }
}
