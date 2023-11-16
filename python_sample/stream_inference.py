import base64
import queue
import threading
import traceback

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
PROJECT_KEY = "YOUR_API_PROJECT_KEY"
REQUEST_TIMEOUT = 10  # seconds
HOP_SIZE = WindowHop("500ms")  # default; or "1s"
DEFAULT_SENSITIVITY = DefaultSensitivity(0)  # default; or in [-2,2]
TAGS_SENSITIVITY = TagsSensitivity(Sing=1)  # example; will alter the results

# Result Abbreviation
RESULT_ABBREVIATION = True
DEFAULT_IM = 1
TAGS_IM = {}  # example {"Male_speech": 1}
###############################################################################


class PyAudioSense:
    def __init__(self, api: AudioSessionApi, session_id: str):
        self.api = api
        self.session_id = session_id

        self.rate = 22050
        chunk = int(self.rate / 2)
        self.buffer = queue.Queue()

        self.audio_stream = PyAudio().open(
            format=paFloat32,
            channels=1,
            rate=self.rate,
            input=True,
            frames_per_buffer=chunk,
            stream_callback=self._fill_buffer,
        )
        self.stop_upload = False

    def _fill_buffer(self, in_data, _frame_count, _time_info, _status_flags):
        self.buffer.put(in_data)
        return None, paContinue

    def generator(self):
        while not self.stop_upload:
            if self.buffer.empty():
                continue
            chunk = self.buffer.get()
            yield chunk

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
    # configuration = Configuration()
    configuration = Configuration(host="https://api.beta.cochl.ai/sense/api/v1")
    configuration.api_key["API_Key"] = PROJECT_KEY
    api = AudioSessionApi(ApiClient(configuration))

    session = api.create_session(
        CreateSession(
            content_type="audio/x-raw; rate=22050; format=f32",
            type=AudioType("stream"),
        )
    )
    session_id = session.session_id

    streamer = PyAudioSense(api, session_id)
    streamer_thread = threading.Thread(target=streamer.upload)
    try:
        # upload audio chunk from microphone in other thread
        streamer_thread.start()

        # get results
        result_abbreviation = ResultAbbreviation()
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
                        print(result)

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
