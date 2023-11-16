const { AudioSessionApi, AudioType, Configuration, WindowHop } = require("@cochl/sense-api")
const { minimizeDetails, displayBufferedResults } = require('./result_abbreviation')
const { readFileSync } = require("fs")

////////////////////////////////////////////////////////////////////////////////////

// Audio Session Params
const API_KEY = "YOUR_API_KEY"
const FILE_PATH = "siren.wav"
const HOP_SIZE = 0.5
const DEFAULT_SENSITIVITY = 0
const TAGS_SENSITIVITY = { }

// Enable Results Abrreviation Display
const RESULT_ABBREVIATION = true

////////////////////////////////////////////////////////////////////////////////////

const contentType = "audio/" + FILE_PATH.split(".").pop()
const file = readFileSync(FILE_PATH)

const conf = new Configuration({
    apiKey: API_KEY,
})

const session = new AudioSessionApi(conf)

async function init(){
    let created = await session.createSession({
        default_sensitivity: DEFAULT_SENSITIVITY,
        tags_sensitivity: TAGS_SENSITIVITY,
        window_hop: HOP_SIZE == 0.5 ? WindowHop._500ms : WindowHop._1s,
        content_type: contentType,
        type: AudioType.File,
        total_size: file.length,
    })
    return created.data.session_id
}

async function upload(id) {
    const size = 1024  * 1024
    for(var sequence = 0; sequence * size < file.length; sequence++) {
        console.log("uploading")
        const chunk = file.slice(sequence * size, (sequence + 1) * size)
        await session.uploadChunk(id, sequence, {
            data: chunk.toString("base64")
        })
    }
}

async function results(id) {
    var nextToken
    let resultsBuffered = []

    process.on('SIGINT', () => {
        console.log(displayBufferedResults(resultsBuffered))
        console.log('Inferencing stopped, deleting session...');
        session.deleteSession(id)
        process.exit(0);
    });
    
    if (RESULT_ABBREVIATION) console.log("<Result Summary>")
    do {
        const result = await session.readStatus(id, undefined, undefined, nextToken)
        nextToken = result.data.inference.page?.next_token
        result.data.inference.results?.forEach(result => {
            if (RESULT_ABBREVIATION) {
                const resultProcessed = minimizeDetails("file", HOP_SIZE, result, resultsBuffered)
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
    if (RESULT_ABBREVIATION) console.log(displayBufferedResults(resultsBuffered))
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