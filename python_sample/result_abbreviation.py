HALF_SECOND = 0.5  # default
ONE_SECOND = 1


class ResultAbbreviation:
    def __init__(
        self,
        enabled=True,
        default_im=0,
        hop_size=HALF_SECOND,
        tags_im: dict[str, int] = None,
    ):
        self.enabled = enabled
        if hop_size != HALF_SECOND and hop_size != ONE_SECOND:
            raise ValueError("Hop size can only be 0.5 or 1")
        self.hop_size = hop_size
        self.default_im = default_im
        self.tags_im = tags_im
        self._buffer = {}
        self._file_mode = False
        self._tag_name_other = "Others"

    def minimize_details(self, results=[], file_ended=False):
        """
        Used for file inference to loop over the results and provide a single result summary
        """
        if not self.enabled:
            return "Result Abbreviation is currently disabled"

        self._file_mode = True
        output = ""

        for frame_result in results:
            line = self.minimize_details_frame(frame_result)
            if not line:
                continue
            output = self._append_line(output, line)

        if file_ended is True:
            to_time = results[-1]["end_time"]
            for tag, (_, from_time, to_time) in self._buffer.items():
                line = f"At {from_time}-{to_time}s, {tag} was detected"
                output = self._append_line(output, line)

        self._file_mode = False
        return output

    def minimize_details_frame(self, frame_result):
        """
        This can be used as you loop over the results
        It will output a merge of the results in ranges
        """
        if not self.enabled:
            return "Result Abbreviation is currently disabled"

        output = ""
        nb_line = 0

        start_time = frame_result["start_time"]
        end_time = frame_result["end_time"]
        treated_tags = []

        for tag in frame_result["tags"]:
            name = tag["name"]
            if name == self._tag_name_other:
                continue

            if name in self._buffer:
                (_, from_time, _) = self._buffer[name]
                self._buffer[name] = (self._im(name), from_time, end_time)
            else:
                self._buffer[name] = (self._im(name), start_time, end_time)
            treated_tags.append(name)

        tags_to_remove = []
        for tag, (im, from_time, to_time) in self._buffer.items():
            if tag in treated_tags:
                continue

            im -= self.hop_size
            if im < 0:
                line = f"At {from_time}-{to_time}s, {tag} was detected"
                nb_line += 1
                output = self._append_line(output, line)
                tags_to_remove.append(tag)
            else:
                self._buffer[tag] = (im, from_time, to_time)

        for tag in tags_to_remove:
            del self._buffer[tag]

        return output

    def clear_buffer(self):
        self._buffer.clear

    def _im(self, tag):
        if self.tags_im is None:
            return self.default_im
        return self.tags_im.get(tag, self.default_im)

    def _append_line(self, result, line):
        if not result:
            return line
        return f"{result}\n{line}"
