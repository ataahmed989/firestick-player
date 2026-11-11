#!/usr/bin/env python3
"""Serves the test videos for the screenshot run, and plays a small Xtream provider with one show, so the episode
screens (the episode's name, Episodes, This episode) can be shown. Run from the media folder: mock-xtream.py PORT."""
import json
import os
import re
import sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

NAMES = {
    1: ["Pilot", "Cat's in the Bag...", "...And the Bag's in the River", "Cancer Man", "Gray Matter",
        "Crazy Handful of Nothin'", "A No-Rough-Stuff-Type Deal"],
    2: ["Seven Thirty-Seven", "Grilled", "Bit by a Dead Bee", "Down", "Breakage", "Peekaboo", "Negro y Azul",
        "Better Call Saul", "4 Days Out", "Over", "Mandala", "Phoenix", "ABQ"],
}
PLOTS = [
    "Walt and Jesse realize how dire their situation is.",
    "Walt's disappearance is met with investigation by Skyler, Marie and Hank.",
    "Walt and Jesse become short on cash, and both must explain where they have been.",
    "Skyler keeps mysteriously leaving the house, and Jesse is having a hard time.",
]


def episode_id(season, number):
    return 1000 * season + number


def series_info():
    seasons = {}
    for season, names in NAMES.items():
        seasons[str(season)] = [{
            "id": str(episode_id(season, n)),
            "episode_num": str(n),
            "season": str(season),
            "title": "Breaking Bad - S%02dE%02d - %s" % (season, n, name),
            "container_extension": "mkv",
            "added": str(1_600_000_000 + episode_id(season, n)),
            "info": {
                "plot": PLOTS[(n - 1) % len(PLOTS)],
                "duration_secs": str(47 * 60),
                "movie_image": "http://10.0.2.2:%d/still-%d.jpg" % (PORT, (n - 1) % 4 + 1),
                "releasedate": "2009-03-%02d" % min(28, n * 2),
            },
        } for n, name in enumerate(names, 1)]
    return {"info": {"name": "Breaking Bad", "tmdb": "1396", "year": "2008"}, "episodes": seasons}


class Handler(SimpleHTTPRequestHandler):
    def send_json(self, value):
        body = json.dumps(value).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urlparse(self.path)
        if url.path.endswith("/player_api.php"):
            action = parse_qs(url.query).get("action", [""])[0]
            if action == "get_series":
                return self.send_json([{"series_id": "77", "name": "EN - Breaking Bad (2008)", "last_modified": "1600002013"}])
            if action == "get_series_info":
                return self.send_json(series_info())
            return self.send_json({"user_info": {"auth": 1, "active_cons": "0", "max_connections": "2"}})
        # Every episode plays the test film, with chapters.
        if re.match(r"^/series/[^/]+/[^/]+/\d+\.mkv$", url.path):
            self.path = "/episode.mkv"
        return super().do_GET()


PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8000
os.chdir(sys.argv[2] if len(sys.argv) > 2 else ".")
ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
