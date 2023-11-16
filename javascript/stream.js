const { AudioSessionApi, AudioType, Configuration, WindowHop } = require("@cochl/sense-api")
const { minimizeDetails, displayBufferedResults } = require('./result_abbreviation')
const mic = require('mic');

////////////////////////////////////////////////////////////////////////////////////

// Audio Session Params
const API_KEY = "YOUR_API_PROJECT_KEY"
const HOP_SIZE = 0.5
const DEFAULT_SENSIBILITY = 0
const TAGS_SENSITIVITY = { }

// Result Abbreviation
const RESULT_ABBREVIATION = true

////////////////////////////////////////////////////////////////////////////////////

const conf = new Configuration({
    apiKey: API_KEY,
})

const session = new AudioSessionApi(conf)

async function init(){
    const created = await session.createSession({
        default_sensitivity: DEFAULT_SENSIBILITY,
        tags_sensitivity: TAGS_SENSITIVITY,
        window_hop: HOP_SIZE == 0.5 ? WindowHop._500ms : WindowHop._1s,
        content_type: "audio/x-raw; rate=22050; format=s16",
        type: AudioType.Stream,
    })
    return created.data.session_id
}

async function upload(id) {
    const size = 22050 * 4 / 2 

    const micInstance = mic({
        rate: '22050', // sample rate, change as per your needs
        channels: '1', // number of channels, change as per your needs
        debug: false,
    });

    var seq = 0
    var buffer = new Uint8Array()

    const micInputStream = micInstance.getAudioStream();

    micInputStream.on('data', async (chunk) => {
        buffer = Buffer.concat([buffer, chunk]);
        if (buffer.length >= size) {
            const toUpload = buffer.slice(0, size);
            buffer = buffer.slice(size);
            await session.uploadChunk(id, seq++, {
                data: Buffer.from(toUpload).toString('base64'),
            });
        }
    });

    micInputStream.on('error', (err) => {
        console.error("Error in Mic stream: ", err);
    });
    
    micInstance.start();
}

async function results(id) {
    process.on('SIGINT', () => {
        console.log(displayBufferedResults(resultsBuffered))
        console.log('Inferencing stopped, deleting session...');
        session.deleteSession(id)
        process.exit(0);
    });
    var nextToken
    let resultsBuffered = []
    do {
        const result = await session.readStatus(id, undefined, undefined, nextToken)
        nextToken = result.data.inference.page?.next_token
        result.data.inference.results?.forEach(result => {
            if (RESULT_ABBREVIATION) {
                const resultProcessed = minimizeDetails("stream", HOP_SIZE, result, resultsBuffered)
                resultsBuffered = resultProcessed.resultsBuffered
                resultProcessed.tagsIMEndedIdx.reverse().forEach(idx => {
                    resultsBuffered.splice(idx, 1);
                })
            }
            else {
                console.log(JSON.stringify(result));
            }
        })
    } while (nextToken != undefined)
}


async function main() {
    const id = await init()
    await Promise.all([
        upload(id),
        results(id)
    ])
}

main().catch(err => {
    console.log(err)
})