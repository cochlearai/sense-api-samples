import base64
import os
import traceback

from cochl_sense_api import ApiClient, Configuration
from cochl_sense_api.api.audio_session_api import AudioSessionApi
from cochl_sense_api.model.audio_chunk import AudioChunk
from cochl_sense_api.model.audio_type import AudioType
from cochl_sense_api.model.create_session import CreateSession
from cochl_sense_api.model.default_sensitivity import DefaultSensitivity
from cochl_sense_api.model.tags_sensitivity import TagsSensitivity
from cochl_sense_api.model.window_hop import WindowHop
from result_abbreviation import ResultAbbreviation

###############################################################################
PROJECT_KEY = "API_PROJECT_KEY"
FILE_PATH = os.path.dirname(__file__) + "/../audio_files/siren.wav"
REQUEST_TIMEOUT = 10  # seconds
HOP_SIZE = WindowHop("500ms")  # default; or "1s"
DEFAULT_SENSITIVITY = DefaultSensitivity(0)  # default; or [-2,2]
TAGS_SENSITIVITY = TagsSensitivity(Sing=1)  # example; will alter the results

# Result Abbreviation
RESULT_ABBREVIATION = True
DEFAULT_IM = 1
TAGS_IM = {}  # example {"Male_speech": 1}
###############################################################################

if __name__ == "__main__":
    configuration = Configuration()
    configuration.api_key["API_Key"] = PROJECT_KEY
    api = AudioSessionApi(ApiClient(configuration))

    # create a new audio session
    file_size = os.stat(FILE_PATH).st_size
    content_type = "audio/" + os.path.splitext(FILE_PATH)[1][1:]
    session = api.create_session(
        CreateSession(
            content_type=content_type,
            type=AudioType("file"),
            total_size=file_size,
            window_hop=HOP_SIZE,
            default_sensitivity=DEFAULT_SENSITIVITY,
            tags_sensitivity=TAGS_SENSITIVITY,
        )
    )

    try:
        with open(FILE_PATH, "rb") as file:
            # upload file by 1Mib chunk
            chunk_sequence = session.chunk_sequence
            while True:
                chunk = file.read(2**20)
                if not chunk:
                    break
                encoded = base64.b64encode(chunk).decode("utf-8")

                print("uploading chunk ", chunk_sequence)
                result = api.upload_chunk(
                    session_id=session.session_id,
                    chunk_sequence=chunk_sequence,
                    audio_chunk=AudioChunk(encoded),
                    _request_timeout=REQUEST_TIMEOUT,
                )
                chunk_sequence = result.chunk_sequence

        # read inference result
        next_token = ""
        i = 0
        file_ended = False
        result_abbreviation = ResultAbbreviation(default_im=DEFAULT_IM, tags_im=TAGS_IM)

        while True:
            resp = api.read_status(
                session_id=session.session_id,
                next_token=next_token,
                _request_timeout=REQUEST_TIMEOUT,
            )
            if resp.get("error"):
                raise Exception(resp.get("error"))

            if "next_token" in resp.inference.page:
                next_token = resp.inference.page.next_token
            else:
                file_ended = True

            if RESULT_ABBREVIATION:
                if i == 0:
                    print("<Result Summary>")
                i += 1

                summary = result_abbreviation.minimize_details(
                    results=resp.inference.results, file_ended=file_ended
                )
                if summary:
                    print(summary)

            else:
                for result in resp.inference.results:
                    print(result)

            if file_ended:
                break

        result_abbreviation.clear_buffer()

    except KeyboardInterrupt:
        pass
    except Exception as err:
        print(
            traceback.format_exc(),
            "(session_id={})".format(session.session_id),
        )

    finally:
        api.delete_session(session_id=session.session_id)
