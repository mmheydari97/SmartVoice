from fastapi import FastAPI, File, UploadFile
from fastapi.responses import StreamingResponse
import io
import wave

app = FastAPI()

@app.post("/upload-audio/")
async def upload_audio(file: UploadFile = File(...)):
    # Read the raw PCM file
    audio_file = await file.read()

    try:
        # Convert PCM to WAV
        wav_stream = io.BytesIO()
        with wave.open(wav_stream, "wb") as wav_file:
            wav_file.setnchannels(1)  # Mono audio
            wav_file.setsampwidth(2)  # 16-bit samples
            wav_file.setframerate(16000)  # Sample rate
            wav_file.writeframes(audio_file)

        wav_stream.seek(0)  # Rewind the stream for reading

        # Return the WAV file as a streaming response
        return StreamingResponse(wav_stream, media_type="audio/wav")

    except Exception as e:
        return {"error": str(e)}
