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
deployment_whisper = os.getenv("DEPLOYMENT_NAME", "whisper-ai-01")  
deployment_gpt40 = os.getenv("DEPLOYMENT_NAME", "gpt-4o")  
subscription_key = os.getenv("AZURE_OPENAI_API_KEY", "")  

# Initialize Azure OpenAI Service client with key-based authentication    
whisper_client = AzureOpenAI(  
    azure_endpoint=endpoint,  
    api_key=subscription_key,  
    api_version="2024-06-01",
)

gpt4o_client = AzureOpenAI(  
    azure_endpoint=endpoint,  
    api_key=subscription_key,  
    api_version="2024-02-15-preview",
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
            # Generate the completion
            transcript = whisper_client.audio.transcriptions.create(
                file=temp_wav_path,  # Pass the actual file path
                model=deployment_whisper
            )
            stt_result = transcript.text
            
            chat_prompt = [
                {
                    "role": "system",
                    "content": [
                        {
                            "type": "text",
                            "text": "You are an AI industrial workspace assistant that gives safety instruction that align closely with the input to the best of your knowledge."
                        }
                    ]
                },
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "text",
                            "text": stt_result
                        }
                    ]
                },
                
            ] 


            completion = gpt4o_client.chat.completions.create(
                model=deployment_gpt40,
                messages=chat_prompt,
                max_tokens=500,  
                temperature=0.7,  
                top_p=0.95,  
                frequency_penalty=0,  
                presence_penalty=0,
                stop=None,  
                stream=False
                )
            
            instruction = completion.choices[0].message.content

            # Return the WAV file as a streaming response
            # return StreamingResponse(wav_stream, media_type="audio/wav")

            # Return the text result as a JSON response
            return JSONResponse(content={"stt": stt_result, "instruction": instruction})

    except Exception as e:
        return {"error": str(e)}
