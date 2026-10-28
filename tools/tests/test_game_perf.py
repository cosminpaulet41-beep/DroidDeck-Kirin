import os
import shutil
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "tools/linuxfs/overlay/usr/local/bin/droiddeck-game-perf"


@unittest.skipUnless(shutil.which("pgrep") and shutil.which("getconf"), "needs procps and getconf")
class GamePerfTest(unittest.TestCase):
    def run_script(self, env, timeout=30):
        return subprocess.run(["bash", str(SCRIPT)], env=dict(os.environ, **env), capture_output=True,
                              text=True, timeout=timeout)

    def test_a_busy_game_thread_shows_up_by_name_with_its_share_of_a_core(self):
        with tempfile.TemporaryDirectory() as tmp:
            exe = Path(tmp) / "Busy Game.exe"
            shutil.copy("/bin/bash", exe)
            busy = subprocess.Popen([str(exe), "-c", "while :; do :; done"])
            try:
                time.sleep(0.5)
                result = self.run_script({"BL_PERF_APP": "1677740", "BL_PERF_INTERVAL": "2", "BL_PERF_ONCE": "1",
                                          "BL_PERF_MATCH": r"Busy Game\.exe"})
            finally:
                busy.kill()
                busy.wait()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stderr, "")
        line = result.stdout.strip()
        self.assertRegex(line, r"^== perf \(app 1677740\): game threads \d+% of one core; busiest: Busy Game\.exe \d+%")
        busiest = int(line.split("Busy Game.exe ")[1].split("%")[0])
        self.assertGreater(busiest, 50)
        self.assertIn("| cores MHz:", line)
        self.assertIn("| gpu:", line)

    def test_nothing_is_written_while_steam_itself_has_the_focus(self):
        with self.assertRaises(subprocess.TimeoutExpired) as caught:
            self.run_script({"BL_PERF_APP": "769", "BL_PERF_INTERVAL": "1", "BL_PERF_ONCE": "1"}, timeout=3)
        self.assertFalse(caught.exception.stdout)


if __name__ == "__main__":
    unittest.main()
