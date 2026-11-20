#!/usr/bin/env python3
"""Serves the test videos for the screenshot run, and plays a small Xtream provider with one show, so the episode
screens (the episode's name, Episodes, This episode) can be shown, plus fictional live channels with an XMLTV guide and
a second, M3U playlist (Live TV fixture; media from make-live-media.sh). Usage: mock-xtream.py PORT [MEDIA_DIR]."""
import json
import time
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



# ---- Live TV fixture (fictional channels; programmes generated around the current time) ---------------------------

LIVE_CATEGORIES = [("1", "News"), ("2", "Sports"), ("3", "Nature"), ("4", "Movies"), ("5", "Kids"), ("6", "Music")]
# number, name, category, EPG id (None: a channel with no guide data)
LIVE_CHANNELS = [
    (1, "Northwind News", "1", "northwind.news"), (2, "Global Report", "1", "global.report"), (3, "Metro 24", "1", "metro24"),
    (4, "Arena Sports", "2", "arena.sports"), (5, "Goal Line", "2", "goal.line"), (6, "Court Side", "2", "court.side"),
    (7, "Wild Earth", "3", "wild.earth"), (8, "Ocean Deep", "3", "ocean.deep"), (9, "Cinema One", "4", "cinema.one"),
    (10, "Classic Reels", "4", "classic.reels"), (11, "Kids Corner", "5", "kids.corner"), (12, "Toon Town", "5", "toon.town"),
    (13, "Pulse Music", "6", "pulse.music"), (14, "Retro Hits", "6", "retro.hits"), (15, "Northwind Weather", "1", "northwind.weather"),
    (16, "Trail TV", "3", None), (17, "Lakeside Docs", "3", "lakeside.docs"), (18, "Midnight Movies", "4", "midnight.movies"),
]
# The second (M3U) playlist: its own channels and its own EPG.
M3U_CHANNELS = [
    (101, "Fireside TV", "Entertainment", "fireside.tv"), (102, "Blue Lagoon", "Entertainment", "blue.lagoon"),
    (103, "Hilltop News", "News", "hilltop.news"), (104, "Summit Sports", "Sports", "summit.sports"),
    (105, "Deep Field", "Documentary", "deep.field"), (106, "Quiz Zone", "Entertainment", "quiz.zone"),
]
TITLES = {
    "1": ["Morning Briefing", "World Today", "Business Hour", "Evening Report", "Newsroom Live", "The Debate", "Weather Watch"],
    "2": ["Matchday Live", "Goals of the Week", "Pre-Game Show", "Tennis Highlights", "Basketball Tonight", "Sports Desk"],
    "3": ["Wild Coasts", "Planet Rivers", "Ocean Giants", "Forest Life", "Into the Canyon", "Northern Lights"],
    "4": ["The Long Road", "City of Glass", "Last Light", "The Quiet Hours", "Beyond Orbit", "Red Harbour"],
    "5": ["Puzzle Pals", "Toon Adventures", "Space Cadets", "Story Time", "Animal Friends", "Robot Club"],
    "6": ["Top 40 Countdown", "Acoustic Sessions", "Retro Hour", "Live at the Hall", "Chill Mix", "Rock Classics"],
    "Entertainment": ["Game Night", "Comedy Club", "Celebrity Kitchen", "The Late Show", "Home Makeover"],
    "News": ["Hilltop Headlines", "Local Matters", "News at Six"],
    "Sports": ["Summit Highlights", "Race Day", "Sports Roundup"],
    "Documentary": ["Deep Field", "Ancient Worlds", "Machines Inside"],
}
DESCRIPTIONS = [
    "A look at the day's main stories with reports and analysis.",
    "Highlights, interviews and expert commentary.",
    "Stunning footage from some of the most remote places on Earth.",
    "A gripping story told over one unforgettable night.",
    "Fun and games for the whole family.",
    "The best tracks of the week, back to back.",
]


def programmes_for(epg_id, category, day_start):
    """Deterministic programmes for one channel from day_start - 1 day to + 2 days."""
    import random
    rnd = random.Random(f"{epg_id}:{day_start}")
    pool = TITLES.get(category, TITLES["1"])
    t = day_start - 86400
    end = day_start + 2 * 86400
    out = []
    while t < end:
        length = rnd.choice([1800, 1800, 3600, 3600, 5400, 7200])
        out.append((t, t + length, rnd.choice(pool), rnd.choice(DESCRIPTIONS)))
        t += length
    return out


def xmltv(channels, now):
    from xml.sax.saxutils import escape
    day_start = int(now) - int(now) % 86400
    fmt = lambda ts: time.strftime("%Y%m%d%H%M%S +0000", time.gmtime(ts))
    parts = ['<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="fireplayer-fixture">']
    for _, name, _, epg in channels:
        if epg:
            parts.append(f'<channel id="{epg}"><display-name>{escape(name)}</display-name></channel>')
    for _, _, cat, epg in channels:
        if not epg:
            continue
        for start, stop, title, desc in programmes_for(epg, cat, day_start):
            parts.append(f'<programme start="{fmt(start)}" stop="{fmt(stop)}" channel="{epg}"><title lang="en">{escape(title)}</title>'
                         f'<desc lang="en">{escape(desc)}</desc></programme>')
    parts.append("</tv>")
    return "\n".join(parts).encode()

class Handler(SimpleHTTPRequestHandler):
    def send_json(self, value):
        body = json.dumps(value).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_bytes(self, body, content_type):
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urlparse(self.path)
        host = self.headers.get("Host") or "10.0.2.2:%d" % PORT
        if url.path.endswith("/player_api.php"):
            action = parse_qs(url.query).get("action", [""])[0]
            if action == "get_series":
                return self.send_json([{"series_id": "77", "name": "EN - Breaking Bad (2008)", "last_modified": "1600002013"}])
            if action == "get_series_info":
                return self.send_json(series_info())
            if action == "get_live_categories":
                return self.send_json([{"category_id": i, "category_name": n, "parent_id": 0} for i, n in LIVE_CATEGORIES])
            if action == "get_live_streams":
                return self.send_json([{
                    "num": n, "name": name, "stream_type": "live", "stream_id": n, "stream_icon": f"http://{host}/logos/{n}.png",
                    "epg_channel_id": epg, "category_id": cat, "tv_archive": 0, "tv_archive_duration": 0,
                } for n, name, cat, epg in LIVE_CHANNELS])
            return self.send_json({"user_info": {"auth": 1, "status": "Active", "active_cons": "0", "max_connections": "2",
                                                 "exp_date": str(int(time.time()) + 180 * 86400)}})
        if url.path.endswith("/xmltv.php"):
            return self.send_bytes(xmltv(LIVE_CHANNELS, time.time()), "application/xml")
        if url.path == "/epg2.xml":
            return self.send_bytes(xmltv(M3U_CHANNELS, time.time()), "application/xml")
        if url.path == "/playlist.m3u":
            lines = [f'#EXTM3U url-tvg="http://{host}/epg2.xml"']
            for n, name, group, epg in M3U_CHANNELS:
                lines.append(f'#EXTINF:-1 tvg-id="{epg}" tvg-name="{name}" tvg-logo="http://{host}/logos/{n}.png" group-title="{group}",{name}')
                lines.append(f"http://{host}/stream/{n}.ts")
            return self.send_bytes("\n".join(lines).encode(), "audio/x-mpegurl")
        live = re.match(r"^/(?:live/[^/]+/[^/]+|stream)/(\d+)\.(ts|m3u8)$", url.path)
        if live:
            n = int(live.group(1))
            cat = next((c for num, _, c, _ in LIVE_CHANNELS if num == n), None) or \
                {"Entertainment": "4", "News": "1", "Sports": "2", "Documentary": "3"}.get(next((g for num, _, g, _ in M3U_CHANNELS if num == n), ""), "1")
            self.path = f"/live/cat-{cat}.ts"
            return super().do_GET()
        # A stand-in for OpenSubtitles' search (only the screenshot build asks here), with long release names.
        if url.path == "/os/subtitles":
            def sub(i, release, downloads, fps=23.976):
                return {"id": str(i), "attributes": {
                    "release": release, "language": "en", "download_count": downloads, "fps": fps,
                    "files": [{"file_id": 9000 + i, "file_name": release + ".srt"}],
                    "feature_details": {"season_number": 1, "episode_number": 1}}}
            return self.send_json({"data": [
                sub(1, "Legion.S01E01.PROPER.HDTV.x264-KILLERS", 165000),
                sub(2, "Legion.S01E01.HDTV.x264-FLEET", 53000),
                sub(3, "Legion.S01E01.1080p.WEB-DL.DD5.1.H264-RARBG", 47000),
                sub(4, "Legion.S01E01.HDTV.x264-FLEET", 174000),
                sub(5, "Legion.S01E01.720p.HDTV.x264-KILLERS", 12000, 25.0),
            ]})
        # Every episode plays the test film, with chapters.
        if re.match(r"^/series/[^/]+/[^/]+/\d+\.mkv$", url.path):
            self.path = "/episode.mkv"
        return super().do_GET()


PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8000
os.chdir(sys.argv[2] if len(sys.argv) > 2 else ".")
ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
