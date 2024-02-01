import base64
import queue
import threading
import traceback
import json

from cochl_sense_api import ApiClient, Configuration
from cochl_sense_api.api.audio_session_api import AudioSessionApi
from cochl_sense_api.model.audio_chunk import AudioChunk
from cochl_sense_api.model.audio_type import AudioType
from cochl_sense_api.model.create_session import CreateSession
from cochl_sense_api.model.default_sensitivity import DefaultSensitivity
from cochl_sense_api.model.tags_sensitivity import TagsSensitivity
from cochl_sense_api.model.window_hop import WindowHop
from pyaudio import PyAudio, paContinue, paFloat32
from result_abbreviation import ResultAbbreviation

###############################################################################
# Audio Session Params
PROJECT_KEY = "PROJECT_KEY"
REQUEST_TIMEOUT = 60  # seconds
SAMPLE_RATE = 22050  # Hz
HOP_SIZE = WindowHop("0.5s")  # default; or "1s"
DEFAULT_SENSITIVITY = DefaultSensitivity(0)  # default; or in [-2,2]
TAGS_SENSITIVITY = TagsSensitivity(Crowd=2, Sing=1)  # example; will alter the results

# Result Abbreviation
RESULT_ABBREVIATION = True
###############################################################################


class PyAudioSense:
    def __init__(self, api: AudioSessionApi, session_id: str):
        self.api = api
        self.session_id = session_id
        self.buffer = queue.Queue()
        self.stop_upload = False

        if HOP_SIZE == WindowHop("0.5s"):
            self.window_hop_samples = int(SAMPLE_RATE / 2)
        elif HOP_SIZE == WindowHop("1s"):
            self.window_hop_samples = SAMPLE_RATE
        else:
            raise ValueError(HOP_SIZE)

        self.audio_stream = PyAudio().open(
            format=paFloat32,
            channels=1,
            rate=SAMPLE_RATE,
            input=True,
            frames_per_buffer=self.window_hop_samples,
            stream_callback=self._fill_buffer,
        )

    def _fill_buffer(self, in_data, _frame_count, _time_info, _status_flags):
        self.buffer.put(in_data)
        return None, paContinue

    def generator(self):
        chunk = bytes()
        while not self.stop_upload:
            if self.buffer.empty():
                continue

            samples = self.buffer.get()
            chunk += samples  # concat bytes
            bytes_per_sample = len(samples) // self.window_hop_samples
            window_size_bytes = SAMPLE_RATE * bytes_per_sample
            window_hop_bytes = self.window_hop_samples * bytes_per_sample

            if len(chunk) >= window_size_bytes:
                yield chunk
                chunk = chunk[window_hop_bytes:]

    def upload(self):
        next_chunk_seq = 0
        for chunk in self.generator():
            encoded = base64.b64encode(chunk).decode("utf-8")
            result = self.api.upload_chunk(
                session_id=self.session_id,
                chunk_sequence=next_chunk_seq,
                audio_chunk=AudioChunk(encoded),
                _request_timeout=REQUEST_TIMEOUT,
            )
            next_chunk_seq = result.chunk_sequence

    def stop(self):
        self.stop_upload = True
        self.audio_stream.stop_stream()
        self.audio_stream.close()


def main():
    configuration = Configuration()
    configuration.api_key["API_Key"] = PROJECT_KEY
    api = AudioSessionApi(ApiClient(configuration))

    session = api.create_session(
        CreateSession(
            window_hop=HOP_SIZE,
            content_type=f"audio/x-raw; rate={SAMPLE_RATE}; format=f32",
            type=AudioType("stream"),
            default_sensitivity=DEFAULT_SENSITIVITY,
            tags_sensitivity=TAGS_SENSITIVITY,
        )
    )
    session_id = session.session_id

    streamer = PyAudioSense(api, session_id)
    streamer_thread = threading.Thread(target=streamer.upload)
    try:
        # upload audio chunk from microphone in other thread
        streamer_thread.start()

        # get results
        result_abbreviation = ResultAbbreviation(hop_size=HOP_SIZE)
        next_token = ""
        while True:
            resp = api.read_status(
                session_id=session_id,
                next_token=next_token,
                _request_timeout=REQUEST_TIMEOUT,
            )
            for result in resp.inference.results:
                if RESULT_ABBREVIATION:
                    summary = result_abbreviation.minimize_details_frame(result)
                    print(f"{summary if summary else '...'}")
                else:
                    for result in resp.inference.results:
                        print(json.dumps(result.to_dict()))
            next_token = resp.inference.page.get("next_token")
            if not next_token:
                break

    except KeyboardInterrupt:
        pass
    except Exception as err:
        print(err, "session_id=", session_id)
        traceback.print_stack()

    finally:
        streamer.stop()
        streamer_thread.join()

        api.delete_session(session_id=session_id)


if __name__ == "__main__":
    main()
