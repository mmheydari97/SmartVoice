import base64
from fastapi import FastAPI, File, UploadFile
from fastapi.responses import StreamingResponse, JSONResponse
import io
import json
from openai import AzureOpenAI  
import os
from pathlib import Path
import tempfile
import wave


endpoint = os.getenv("ENDPOINT_URL", "https://azopenai-01.openai.azure.com/")  
deployment = os.getenv("DEPLOYMENT_NAME", "whisper-ai-01")  
subscription_key = os.getenv("AZURE_OPENAI_API_KEY", "")  

# Initialize Azure OpenAI Service client with key-based authentication    
client = AzureOpenAI(  
    azure_endpoint=endpoint,  
    api_key=subscription_key,  
    api_version="2024-06-01",
)

app = FastAPI()

@app.post("/upload-audio/")
async def upload_audio(file: UploadFile = File(...)):
    # TODO: How does the code handle long processes without timeout
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
        
        with tempfile.NamedTemporaryFile(delete=True, suffix=".wav") as temp_wav_file:
            temp_wav_file.write(wav_stream.read())
            temp_wav_file.flush()  # Ensure data is written to disk

            temp_wav_path = Path(os.path.join(Path(__file__).parent, temp_wav_file.name))
            print(f"Path that is created: {temp_wav_path}")
            # Generate the completion
            completion = client.audio.transcriptions.create(
                file=temp_wav_path,  # Pass the actual file path
                model=deployment
            )
            result = completion.to_dict()
            print(f"Received from Azure: {result} with type: {type(result)}")

        # Return the WAV file as a streaming response
        # return StreamingResponse(wav_stream, media_type="audio/wav")

        # Return the text result as a JSON response
        return JSONResponse(content=result)

    except Exception as e:
        return {"error": str(e)}
