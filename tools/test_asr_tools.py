import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import asr_common
import asr_eval
import qwen_asr_server


def word(text, start, end, space_before=None):
    return {"word": text, "start": start, "end": end, "space_before": space_before}


class LrcTest(unittest.TestCase):
    def test_strips_time_and_meta_tags(self):
        lrc = "[ar:Artist]\n[ti:Title]\n[00:12.34]첫 줄\n[01:02]second line\n[02:03.4]<02:03.9>三行目\n\n[offset:+500]"
        self.assertEqual(asr_common.strip_lrc(lrc), "첫 줄\nsecond line\n三行目")


class NormalizeTest(unittest.TestCase):
    def test_normalize_drops_punctuation_and_case(self):
        self.assertEqual(asr_common.normalize_text("Hello, World!  ♪ [Chorus]"), "hello world chorus")
        self.assertEqual(asr_common.normalize_text("ｶﾀｶﾅ、テスト。"), "カタカナ テスト")

    def test_levenshtein(self):
        self.assertEqual(asr_common.levenshtein("kitten", "sitting"), 3)
        self.assertEqual(asr_common.levenshtein("", "abc"), 3)
        self.assertEqual(asr_common.levenshtein(["a", "b"], ["a", "c", "b"]), 1)

    def test_cer_wer_latin(self):
        self.assertAlmostEqual(asr_common.cer("hello world", "hello world"), 0.0)
        self.assertAlmostEqual(asr_common.wer("the cat sat", "the cat sit"), 1 / 3)

    def test_cer_korean(self):
        self.assertAlmostEqual(asr_common.cer("사랑해요 그대", "사랑해요. 그대!"), 0.0)
        self.assertAlmostEqual(asr_common.cer("사랑해요", "사람해요"), 0.25)
        self.assertAlmostEqual(asr_common.wer("사랑해요 그대", "사랑해요 그때"), 0.5)

    def test_cer_japanese(self):
        self.assertAlmostEqual(asr_common.cer("君の名前を", "君の名前を"), 0.0)
        self.assertAlmostEqual(asr_common.cer("君の名前を", "君の名を"), 0.2)

    def test_empty_reference(self):
        self.assertIsNone(asr_common.cer("...", "x"))


class LanguageTest(unittest.TestCase):
    def test_code_to_name_and_back(self):
        self.assertEqual(asr_common.language_name("ko"), "Korean")
        self.assertEqual(asr_common.language_name("JA"), "Japanese")
        self.assertEqual(asr_common.language_name("english"), "English")
        self.assertIsNone(asr_common.language_name("xx"))
        self.assertEqual(asr_common.language_code("Korean"), "ko")
        self.assertEqual(asr_common.language_code("Chinese,English"), "zh")
        self.assertEqual(asr_common.language_code(""), "")

    def test_hint(self):
        self.assertEqual(asr_common.language_hint("アイドル - YOASOBI"), "ja")
        self.assertEqual(asr_common.language_hint("아이유 - 밤편지"), "ko")
        self.assertIsNone(asr_common.language_hint("Adele - Hello"))


class UrlTest(unittest.TestCase):
    def test_appends_path_only_without_one(self):
        self.assertEqual(asr_common.transcription_url("http://gpu-pc-1:8000"), "http://gpu-pc-1:8000/v1/audio/transcriptions")
        self.assertEqual(asr_common.transcription_url("http://gpu-pc-1:8000/"), "http://gpu-pc-1:8000/v1/audio/transcriptions")
        self.assertEqual(asr_common.transcription_url("http://gpu-pc-2:8178/inference"), "http://gpu-pc-2:8178/inference")

    def test_endpoint_argument(self):
        self.assertEqual(asr_eval.parse_endpoint("qwen=http://host:8001"), ("qwen", "http://host:8001/v1/audio/transcriptions"))


class SegmentTest(unittest.TestCase):
    def test_gap_split_and_latin_join(self):
        words = [word("hello", 0.0, 0.4), word("world", 0.5, 0.9), word("again", 2.6, 3.0)]
        segments = asr_common.build_segments(words)
        self.assertEqual([s["text"] for s in segments], ["hello world", "again"])
        self.assertEqual(segments[0]["start"], 0.0)
        self.assertEqual(segments[0]["end"], 0.9)
        self.assertEqual(segments[1]["start"], 2.6)

    def test_held_note_gap_keeps_line(self):
        words = [word("사랑", 0.0, 0.5), word("한다는", 1.7, 2.1, True), word("말이에요", 3.0, 3.8, True)]
        self.assertEqual([s["text"] for s in asr_common.build_segments(words)], ["사랑 한다는 말이에요"])

    def test_short_fragment_merges_into_neighbour(self):
        words = [word("差し", 0.0, 0.5), word("た", 2.5, 2.7), word("空と君の姿", 2.8, 4.0)]
        self.assertEqual([s["text"] for s in asr_common.build_segments(words)], ["差した空と君の姿"])

    def test_majority_language_by_script(self):
        texts = ["沈むように溶けてゆくように", "Let's hide in the shadows", "Kimi ni shika mienai", "騒がしい日々に笑えない君に"]
        self.assertEqual(asr_common.majority_language(texts), "Japanese")
        self.assertEqual(asr_common.stray_indices(texts, "Japanese"), [1, 2])
        self.assertEqual(asr_common.majority_language(["이 밤 그날의 반딧불을", "hello"]), "Korean")
        self.assertEqual(asr_common.script_language("夜空"), "Chinese")
        self.assertIsNone(asr_common.majority_language(["", None, "..."]))

    def test_length_cap_prefers_word_boundary(self):
        words = [word("溶", 0.0, 0.3), word("けて", 0.9, 1.2), word("ゆくように、", 1.3, 2.0), word("二", 2.4, 2.6), word("人", 2.7, 3.0)]
        segments = asr_common.build_segments(words, max_chars=9, min_chars=1)
        self.assertEqual([s["text"] for s in segments], ["溶けてゆくように、", "二人"])

    def test_sentence_punctuation_split(self):
        words = [word("Hello.", 0.0, 0.4), word("World", 0.5, 0.9)]
        self.assertEqual([s["text"] for s in asr_common.build_segments(words)], ["Hello.", "World"])

    def test_cjk_join_without_space(self):
        words = [word("君", 0.0, 0.2), word("の", 0.2, 0.3), word("名前", 0.3, 0.6), word("hello", 0.7, 1.0), word("世界", 1.0, 1.3)]
        self.assertEqual(asr_common.build_segments(words)[0]["text"], "君の名前 hello 世界")

    def test_explicit_spacing_wins(self):
        words = [word("사랑", 0.0, 0.2), word("해요", 0.2, 0.4, False), word("그대", 0.5, 0.7, True)]
        self.assertEqual(asr_common.build_segments(words)[0]["text"], "사랑해요 그대")

    def test_length_cap_splits_at_largest_gap(self):
        words = [word(f"w{i}", i * 0.5, i * 0.5 + 0.3) for i in range(30)]
        words[10]["start"] = 5.2
        segments = asr_common.build_segments(words, max_seconds=100.0, max_chars=40)
        self.assertGreater(len(segments), 1)
        self.assertEqual(segments[0]["text"].split()[-1], "w9")
        for segment in segments:
            self.assertLessEqual(len(segment["text"].replace(" ", "")), 40)

    def test_duration_cap(self):
        words = [word("alpha", 0.0, 6.0), word("bravo", 6.1, 12.5)]
        segments = asr_common.build_segments(words, max_seconds=10.0)
        self.assertEqual(len(segments), 2)

    def test_restore_spacing_from_text(self):
        restored = asr_common.restore_spacing("나는 너를 사랑해, 정말.", ["나", "는", "너", "를", "사랑해", "정말"])
        self.assertEqual([r["space_before"] for r in restored], [False, False, True, False, True, True])
        self.assertEqual(restored[4]["word"], "사랑해,")
        self.assertEqual(restored[5]["word"], "정말.")
        self.assertEqual(asr_common.join_words(restored), "나는 너를 사랑해, 정말.")

    def test_restore_spacing_unknown_token_falls_back(self):
        restored = asr_common.restore_spacing("hello world", ["hello", "zzz", "world"])
        self.assertIsNone(restored[1]["space_before"])
        self.assertEqual(asr_common.join_words(restored), "hello zzz world")


class VocalRegionTest(unittest.TestCase):
    def test_detects_regions_and_merges_short_gaps(self):
        frame = 0.1
        energy = [0.0] * 20 + [1.0] * 30 + [0.0] * 3 + [1.0] * 20 + [0.0] * 50 + [0.9] * 10 + [0.0] * 10
        regions = asr_common.vocal_regions(energy, frame, threshold_ratio=0.1, merge_gap=0.6, pad=0.0)
        self.assertEqual([(round(s, 1), round(e, 1)) for s, e in regions], [(2.0, 7.3), (12.3, 13.3)])

    def test_silence_gives_nothing(self):
        self.assertEqual(asr_common.vocal_regions([0.0] * 50, 0.1), [])

    def test_padding_is_clipped(self):
        regions = asr_common.vocal_regions([1.0] * 10, 0.1, pad=0.5)
        self.assertEqual(regions, [(0.0, 1.0)])

    def test_tiny_blips_dropped(self):
        energy = [0.0] * 10 + [1.0] + [0.0] * 10 + [1.0] * 10
        regions = asr_common.vocal_regions(energy, 0.1, min_region=0.3, pad=0.0)
        self.assertEqual([(round(s, 1), round(e, 1)) for s, e in regions], [(2.1, 3.1)])


class ChunkTest(unittest.TestCase):
    def test_long_region_splits_at_quietest_point(self):
        frame = 0.1
        energy = [1.0] * 600
        energy[320] = 0.0
        chunks = asr_common.chunk_regions([(0.0, 60.0)], energy, frame, 30.0)
        self.assertEqual(len(chunks), 2)
        self.assertAlmostEqual(chunks[0][1], 32.05)
        self.assertAlmostEqual(chunks[1][0], 32.05)
        self.assertEqual(chunks[-1][1], 60.0)
        for start, end in chunks:
            self.assertLessEqual(end - start, 35.0)

    def test_long_region_keeps_pieces_near_cap(self):
        chunks = asr_common.chunk_regions([(10.0, 110.0)], [1.0] * 1200, 0.1, 30.0)
        self.assertEqual(len(chunks), 4)
        self.assertEqual(chunks[0][0], 10.0)
        self.assertEqual(chunks[-1][1], 110.0)
        for (_, previous_end), (next_start, _) in zip(chunks, chunks[1:]):
            self.assertEqual(previous_end, next_start)
        for start, end in chunks:
            self.assertLessEqual(end - start, 35.0)
            self.assertGreaterEqual(end - start, 20.0)

    def test_short_region_untouched(self):
        self.assertEqual(asr_common.chunk_regions([(3.0, 20.0)], [1.0] * 200, 0.1, 30.0), [(3.0, 20.0)])

    def test_offsets(self):
        segments = [{"start": 0.5, "end": 1.5, "text": "a", "words": [word("a", 0.5, 1.5)]}]
        moved = asr_common.offset_segments(segments, 10.0)
        self.assertEqual(moved[0]["start"], 10.5)
        self.assertEqual(moved[0]["words"][0]["end"], 11.5)
        self.assertEqual(segments[0]["start"], 0.5)


class ResponseTest(unittest.TestCase):
    def test_verbose_json_shape(self):
        segments = [
            {"start": 0.0, "end": 1.0, "text": "hello world", "words": [word("hello", 0.0, 0.4), word("world", 0.5, 1.0)]},
            {"start": 2.0, "end": 3.0, "text": "again", "words": [word("again", 2.0, 3.0)]},
        ]
        payload = asr_common.verbose_json("Korean", 3.5, segments)
        self.assertEqual(payload["task"], "transcribe")
        self.assertEqual(payload["language"], "ko")
        self.assertEqual(payload["duration"], 3.5)
        self.assertEqual(payload["text"], "hello world again")
        self.assertEqual([w["word"] for w in payload["words"]], ["hello", "world", "again"])
        self.assertEqual(payload["words"][1]["start"], 0.5)
        for segment in payload["segments"]:
            self.assertEqual(set(segment), {"start", "end", "text"})
        self.assertEqual(asr_eval.hypothesis_text(payload), "hello world again")

    def test_text_only_mode(self):
        payload = asr_common.verbose_json("", 12.0, [{"start": 0.0, "end": 12.0, "text": "가사"}])
        self.assertEqual(payload["words"], [])
        self.assertEqual(payload["segments"][0]["text"], "가사")
        self.assertEqual(asr_eval.hypothesis_text({"segments": [{"text": "a"}, {"text": "b"}]}), "a\nb")


class ServerHelperTest(unittest.TestCase):
    def test_settings_from_env(self):
        old = dict(os.environ)
        os.environ.update({"QWEN_ASR_SEPARATE": "vad", "QWEN_ASR_PORT": "8001", "QWEN_ASR_ALIGN": "0", "QWEN_ASR_DEVICE": "cpu"})
        try:
            settings = qwen_asr_server.load_settings([])
        finally:
            os.environ.clear()
            os.environ.update(old)
        self.assertEqual(settings.separate, "vad")
        self.assertEqual(settings.port, 8001)
        self.assertFalse(settings.align)
        self.assertEqual(settings.dtype, "float32")
        self.assertEqual(settings.attn, "sdpa")
        self.assertEqual(settings.model, "Qwen/Qwen3-ASR-1.7B")

    def test_chunk_segments_without_alignment(self):
        segments = qwen_asr_server.chunk_segments("가사 한 줄", [], 7.5)
        self.assertEqual(segments, [{"start": 0.0, "end": 7.5, "text": "가사 한 줄", "words": []}])

    def test_chunk_segments_with_items(self):
        items = [{"word": "hello", "start": 0.1, "end": 0.4}, {"word": "world", "start": 0.5, "end": 0.9}, {"word": "bye", "start": 3.0, "end": 3.3}]
        segments = qwen_asr_server.chunk_segments("Hello world. Bye", items, 4.0)
        self.assertEqual([s["text"] for s in segments], ["Hello world.", "Bye"])

    def test_alignment_items_millisecond_guard(self):
        class Item:
            def __init__(self, text, start, end):
                self.text, self.start_time, self.end_time = text, start, end

        class Result:
            items = [Item("a", 100, 400), Item("b", 500, 900)]

        parsed = qwen_asr_server.alignment_items(Result(), 1.0)
        self.assertAlmostEqual(parsed[0]["start"], 0.1)
        self.assertAlmostEqual(parsed[1]["end"], 0.9)
        self.assertEqual(qwen_asr_server.alignment_items(None, 1.0), [])


class MultipartTest(unittest.TestCase):
    def test_matches_java_fields(self):
        body = asr_eval.multipart("b", b"RIFF", "ko")
        self.assertIn(b'name="file"; filename="audio.wav"\r\nContent-Type: audio/wav\r\n\r\nRIFF\r\n', body)
        self.assertIn(b'name="language"\r\n\r\nko\r\n', body)
        self.assertIn(b'name="response_format"\r\n\r\nverbose_json\r\n', body)
        self.assertIn(b'name="max_context"\r\n\r\n0\r\n', body)
        self.assertTrue(body.endswith(b"--b--\r\n"))
        self.assertNotIn(b'name="language"', asr_eval.multipart("b", b"RIFF", None))


if __name__ == "__main__":
    unittest.main()
