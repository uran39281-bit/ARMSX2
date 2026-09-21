import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("benchmark", Path(__file__).parents[1] / "tools/benchmark.py")
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)


def line(ms, crc="1234abcd", values="60,30,100,16.667,80,40"):
    return f"I BlackIceBench: v1,{ms},{crc},{values}"


class BenchmarkTests(unittest.TestCase):
    def test_refresh_is_not_game_fps(self):
        run = bench.segments("\n".join(line(t) for t in range(0, 3001, 500)))[0]
        result = bench.summarize(run, 1, 2)
        self.assertEqual(result["refresh_fps_sample_mean"], 60)
        self.assertEqual(result["internal_fps_sample_mean"], 30)
        self.assertEqual(result["samples"], 4)

    def test_excludes_warmup_but_keeps_slow_gameplay(self):
        text = "\n".join([line(0), line(500), line(1000, values="10,-1,16.7,100,99,20"), line(1500, values="10,-1,16.7,100,99,20"), line(2000)])
        result = bench.summarize(bench.segments(text)[0], 1, 1)
        self.assertEqual(result["refresh_fps_sample_mean"], 10)
        self.assertIsNone(result["internal_fps_sample_mean"])
        self.assertEqual(result["below_95_percent_speed_sample_fraction"], 1)

    def test_pause_game_change_and_restart_split_segments(self):
        text = "\n".join([line(0), line(500), line(4000), line(4500, crc="abcdef12"), line(0, crc="abcdef12")])
        self.assertEqual([len(s) for s in bench.segments(text)], [2, 1, 1, 1])

    def test_rejects_malformed_and_nonfinite_samples(self):
        text = "\n".join(["unrelated log", line(0, values="nan,30,100,16,80,40"), line(0, values="60,30"), line(0, crc="00000000"), line(0, values="-1,30,100,16,80,40"), line(500)])
        self.assertEqual(len(bench.segments(text)[0]), 1)

    def test_rejects_short_recording(self):
        run = bench.segments("\n".join(line(t) for t in (0, 500, 1000)))[0]
        with self.assertRaises(ValueError):
            bench.summarize(run, 0, 2)


if __name__ == "__main__":
    unittest.main()
