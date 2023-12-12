// Result Abbreviation params
const DEFAULT_IM = 0
const TAGS_IM = { } // example {Male_speech: 1}

function formatTimeDisplay(time) {
    return Number.isInteger(time) ? time.toFixed(1) : time
}

function displayBufferedResults(resultsBuffered) {
    resultDisplay = resultsBuffered.map(tag => {
        return `At ${formatTimeDisplay(tag.start_time)}-${formatTimeDisplay(tag.end_time)}s, [${tag.tag_name}] was detected`
    }).join("\n")
    return resultDisplay
}

function processBufferedTags(inferenceMode, hopSize, result, resultsBuffered) {
    const tagsIMEnded = []
    const minimumIM = hopSize == 0.5 && DEFAULT_IM == 0 ? -0.5 : 0
    resultsBuffered.forEach((tagSaved, ridx) => {
        const tagDetectedIdx = result.tags.findIndex(tagDetected => tagDetected.name === tagSaved.tag_name);
        if (inferenceMode == "file") {
            if (tagDetectedIdx == -1) {
                resultsBuffered[ridx].im = resultsBuffered[ridx].im - hopSize
            }
            else {
                resultsBuffered[ridx].end_time = result.end_time
                resultsBuffered[ridx].im = TAGS_IM[resultsBuffered[ridx].tag_name] ?? DEFAULT_IM
            }
            if (resultsBuffered[ridx].im < minimumIM) {
                tagsIMEnded.push({ id: ridx, ...resultsBuffered[ridx] })
            }
        } else {
            if (tagDetectedIdx == -1) {
                tagsIMEnded.push({ id: ridx, ...resultsBuffered[ridx] })
            } else {
                resultsBuffered[ridx].end_time = result.end_time
            }
        }
    })
    return {
        tagsIMEnded,
        updatedResultsBuffered: resultsBuffered,
    }
}

function minimizeDetails(inferenceMode, hopSize, result, resultsBuffered) {
    result.tags.forEach(tagDetected => {
        const tagSavedIdx = resultsBuffered.findIndex(tagSaved => tagSaved.tag_name === tagDetected.name);
        if (tagDetected.name != "Others" && tagSavedIdx === -1) {
            resultsBuffered.push({
                tag_name: tagDetected.name,
                im: TAGS_IM[tagDetected.name] ?? DEFAULT_IM,
                start_time: result.start_time,
                end_time: result.end_time
            })
        }
    })
    const bufferedTagsProcessed = processBufferedTags(inferenceMode, hopSize, result, resultsBuffered)
    
    if (bufferedTagsProcessed.tagsIMEnded.length > 0) {
        console.log(displayBufferedResults(bufferedTagsProcessed.tagsIMEnded))
    } else if (inferenceMode == "stream") {
        console.log('...')
    }
    return {
        resultsBuffered: resultsBuffered,
        tagsIMEndedIdx: bufferedTagsProcessed.tagsIMEnded.map(tag => tag.id),
    }
}

module.exports = { minimizeDetails, displayBufferedResults };
