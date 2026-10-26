#!/usr/bin/env python3
"""Replay real street geometry into the unchanged, signed release on a fresh AVD.

The replay uses the emulator's GNSS controller, never mock-provider app code or
direct database inserts. Root is used only to configure first-run prompts and
copy the emulator's private SQLite files for inspection. No real account is used.
"""
import argparse
import bisect
import hashlib
import json
import math
import os
from pathlib import Path
import random
import re
import shutil
import sqlite3
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = 'com.roadconquest.app'
EARTH = 6371000
# On the API 35 runner, setGps's documented m/s value reaches Android as knots:
# 12.0 becomes exactly 6.17333 m/s. Compensate only in the emulator input and
# verify the native provider value before treating this as a valid driving test.
EMULATOR_SPEED_INPUT_SCALE = 3600 / 1852
SDK_ADB = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', '/usr/local/lib/android/sdk'))) / 'platform-tools/adb'
ADB = str(SDK_ADB) if SDK_ADB.is_file() else shutil.which('adb') or 'adb'
EMULATOR_SERIAL = os.environ.get('ROADCONQUEST_EMULATOR_SERIAL', 'emulator-5554')


def meters(a, b):
    lat = math.radians((a[1] + b[1]) / 2)
    return math.hypot(math.radians(a[0] - b[0]) * math.cos(lat),
                      math.radians(a[1] - b[1])) * EARTH


def course(a, b):
    return math.degrees(math.atan2((b[0] - a[0]) * math.cos(math.radians(a[1])),
                                   b[1] - a[1])) % 360


def offset(point, east, north):
    return [point[0] + math.degrees(east / EARTH) / math.cos(math.radians(point[1])),
            point[1] + math.degrees(north / EARTH)]


def route_plan():
    reference = json.loads((Path(__file__).parent / 'fixtures/philadelphia-drive.json').read_text())
    path, turns, cumulative = [], [], []
    total = 0.0
    for leg in reference['routes'][0]['legs']:
        for step in leg['steps']:
            coords = step['geometry']['coordinates']
            if not coords:
                continue
            if path and meters(path[-1], coords[0]) > 1:
                raise AssertionError('Reference steps are disconnected')
            if step['maneuver']['type'] == 'turn':
                turns.append({'at_m': total, 'point': coords[0],
                              'direction': step['maneuver'].get('modifier'),
                              'road': step['name'] or '(unnamed connector)'})
            for coord in coords:
                if path:
                    distance = meters(path[-1], coord)
                    if distance < .01:
                        continue
                    total += distance
                path.append(coord)
                cumulative.append(total)

    def at(distance):
        i = min(bisect.bisect_right(cumulative, distance), len(path) - 1)
        if i == 0:
            return path[0]
        f = (distance - cumulative[i-1]) / (cumulative[i] - cumulative[i-1])
        return [path[i-1][j] + f * (path[i][j] - path[i-1][j]) for j in (0, 1)]

    def driving_at(distance):
        turn = min(turns, key=lambda t: abs(t['at_m'] - distance))
        radius = 10
        if abs(distance-turn['at_m']) >= radius:
            return at(distance)
        entry, exit = at(max(0,turn['at_m']-radius)), at(min(total,turn['at_m']+radius))
        f = (distance-turn['at_m']+radius)/(2*radius)
        return [(1-f)**2*entry[j] + 2*(1-f)*f*turn['point'][j] + f*f*exit[j] for j in (0,1)]

    rows = []
    rng = random.Random(1937)
    distance = 0.0
    elapsed = 0.0
    stopped = False
    # Stop just before the left turn into Chestnut Street.
    stop_at = next(t['at_m'] for t in turns if t['road'] == 'Chestnut Street') - 12
    while distance < total:
        nearest = min(abs(distance - turn['at_m']) for turn in turns)
        speed = 4.5 + 7.5 * min(1, nearest / 45)
        dt = 6 if 1450 < distance < 1775 else 3
        if not stopped and distance >= stop_at:
            for _ in range(15):
                elapsed += 3
                # Small sub-meter stopped jitter, without invented driving speed.
                point = offset(driving_at(distance), rng.uniform(-.6, .6), rng.uniform(-.6, .6))
                rows.append({'t': elapsed, 's': distance, 'position': point,
                             'speed': 0, 'bearing': course(at(distance), at(distance + 1)),
                             'phase': 'traffic-light-stop'})
            stopped = True
        for _ in range(dt):
            nearest = min(abs(distance - turn['at_m']) for turn in turns)
            speed = 4.5 + 7.5 * min(1, nearest / 45)
            distance = min(total, distance + speed)
            if not stopped:
                distance = min(distance, stop_at)
        elapsed += dt
        point = driving_at(distance)
        heading = course(driving_at(max(0, distance - 2)), driving_at(min(total, distance + 2)))
        if distance > 750:
            # A consistent lane offset plus bounded position noise (not perfect centerline GPS).
            lateral = max(-3.5, min(3.5, 1.5 + rng.gauss(0, 1.0)))
            point = offset(point, math.cos(math.radians(heading)) * lateral,
                           -math.sin(math.radians(heading)) * lateral)
        phase = 'sparse-noisy-fixes' if dt == 6 else ('noisy-fixes' if distance > 750 else 'clean-fixes')
        rows.append({'t': elapsed, 's': distance, 'position': point,
                     'speed': speed, 'bearing': heading, 'phase': phase})
    return {'path': path, 'cumulative': cumulative, 'turns': turns,
            'distance_m': total, 'duration_s': elapsed, 'rows': rows}, at


def adb(*args, check=True):
    result = subprocess.run([ADB, '-s', EMULATOR_SERIAL, *map(str, args)], check=False,
                            capture_output=True, timeout=45)
    if check and result.returncode:
        raise RuntimeError(f'ADB {args} failed: {result.stderr.decode(errors="replace").strip()} '
                           f'{result.stdout.decode(errors="replace").strip()}')
    return result.stdout.decode(errors='replace').strip()


def screenshot(out, name):
    result = subprocess.run([ADB, '-s', EMULATOR_SERIAL, 'exec-out', 'screencap', '-p'],
                            capture_output=True, timeout=30, check=True)
    (out / (name + '.png')).write_bytes(result.stdout)


def dump_ui(out, name):
    adb('shell', 'uiautomator', 'dump', '/sdcard/drive-ui.xml')
    xml = adb('shell', 'cat', '/sdcard/drive-ui.xml')
    (out / (name + '.xml')).write_text(xml)
    return ET.fromstring(xml)


def tap_id(root, resource_id):
    for node in root.iter('node'):
        if node.get('resource-id') == PACKAGE + ':id/' + resource_id:
            x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
            adb('shell', 'input', 'tap', (x1+x2)//2, (y1+y2)//2)
            return True
    return False


class GpsController:
    def __init__(self):
        import grpc
        from google.protobuf import descriptor_pb2, descriptor_pool, empty_pb2, message_factory
        # The small GpsState schema from AOSP emulator_controller.proto. The emulator
        # declares speed in m/s and bearing in degrees; Android supplies fix timing.
        schema = descriptor_pb2.FileDescriptorProto(
            name='drive-gps.proto', package='android.emulation.control', syntax='proto3')
        message = schema.message_type.add(name='GpsState')
        for name, number, kind in [('passiveUpdate',1,8), ('latitude',2,1),
                                   ('longitude',3,1), ('speed',4,1), ('bearing',5,1),
                                   ('altitude',6,1), ('satellites',7,5)]:
            message.field.add(name=name, number=number, type=kind, label=1)
        pool = descriptor_pool.DescriptorPool()
        pool.Add(schema)
        self.state = message_factory.GetMessageClass(
            pool.FindMessageTypeByName('android.emulation.control.GpsState'))
        self.channel = grpc.insecure_channel('127.0.0.1:8554')
        grpc.channel_ready_future(self.channel).result(timeout=20)
        self.send = self.channel.unary_unary(
            '/android.emulation.control.EmulatorController/setGps',
            request_serializer=self.state.SerializeToString,
            response_deserializer=empty_pb2.Empty.FromString)

    def fix(self, point, speed, bearing):
        self.send(self.state(passiveUpdate=False, latitude=point[1], longitude=point[0],
                             speed=speed * EMULATOR_SPEED_INPUT_SCALE, bearing=bearing,
                             altitude=10, satellites=10), timeout=5)


def check_native_speed(location, rows):
    gps = re.search(r'last location=Location\[gps (-?[\d.]+),(-?[\d.]+)[^\n]* vel=([\d.]+)', location)
    assert gps, 'Emulator did not expose native GPS speed'
    latitude, longitude, native_speed = map(float, gps.groups())
    row = min(rows, key=lambda r: meters([longitude, latitude], r['position']))
    assert meters([longitude, latitude], row['position']) < 2, 'Cannot associate native fix with replay row'
    assert abs(native_speed - row['speed']) < .03, (
        f'Invalid emulator speed: native {native_speed} m/s, commanded {row["speed"]} m/s')
    return {'native_speed_mps': native_speed, 'planned_speed_mps': row['speed'],
            'emulator_input_scale': EMULATOR_SPEED_INPUT_SCALE, 'row_time_s': row['t']}


def check_gps_clock(location):
    gps = re.search(r'last location=Location\[gps[^\n]*et=\+([^ ]+)', location)
    assert gps, 'Emulator did not provide a GPS timestamp'
    units = {'d':86400, 'h':3600, 'm':60, 's':1, 'ms':.001}
    elapsed = sum(float(n)*units[u] for n,u in re.findall(r'(\d+(?:\.\d+)?)(ms|d|h|m|s)',gps[1]))
    uptime = float(adb('shell','cat','/proc/uptime').split()[0])
    assert 0 <= uptime-elapsed <= 15, f'Invalid emulator GPS elapsed time: {gps[1]}, uptime {uptime}s'


def snapshot(out):
    # The emulator is fresh and disposable; stopping the app closes/checkpoints its DB.
    adb('shell', 'am', 'force-stop', PACKAGE)
    adb('pull', f'/data/user/0/{PACKAGE}/databases', str(out / 'databases'))
    db = sqlite3.connect(out / 'databases/roadconquest.db')
    db.row_factory = sqlite3.Row
    points = [dict(r) for r in db.execute('SELECT * FROM track_points ORDER BY id')]
    roads = [dict(r) for r in db.execute('SELECT * FROM roads')]
    db.close()
    return points, roads


def analyze(plan, at, points, roads):
    segments = []
    for road in roads:
        geometry = json.loads(road['geometry_json'])
        segments.extend(zip(geometry, geometry[1:]))

    def distance_to_roads(point):
        best = math.inf
        for a, b in segments:
            cosine = math.cos(math.radians(point[1]))
            ax = math.radians(a[0]-point[0]) * cosine * EARTH
            ay = math.radians(a[1]-point[1]) * EARTH
            bx = math.radians(b[0]-point[0]) * cosine * EARTH
            by = math.radians(b[1]-point[1]) * EARTH
            dx, dy = bx-ax, by-ay
            f = min(1, max(0, -(ax*dx+ay*dy)/(dx*dx+dy*dy))) if dx*dx+dy*dy else 0
            best = min(best, math.hypot(ax+f*dx, ay+f*dy))
        return best

    corner_results = []
    for i, turn in enumerate(plan['turns']):
        start = max(0, turn['at_m']-25)
        end = min(plan['distance_m'], turn['at_m']+25)
        samples = [distance_to_roads(at(start+j)) for j in range(math.ceil(end-start)+1)]
        missing = longest = 0
        for error in samples:
            missing = missing+1 if error > 3.35 else 0
            longest = max(longest, missing)
        corner_results.append({**turn, 'index': i+1,
                               'center_error_m': distance_to_roads(turn['point']),
                               'coverage_within_3_35m': sum(d<=3.35 for d in samples)/len(samples),
                               'longest_uncovered_m': longest})
    route_errors = [distance_to_roads(at(s)) for s in range(int(plan['distance_m'])+1)]
    max_jump = max((meters([a['longitude'],a['latitude']], [b['longitude'],b['latitude']])
                    for a,b in zip(points,points[1:])), default=0)
    return {'planned_distance_m': plan['distance_m'], 'duration_s': plan['duration_s'],
            'recorded_points': len(points), 'matched_points': sum(p['matched'] for p in points),
            'pending_points': sum(not p['matched'] for p in points),
            'recorded_distance_m': sum(p['distance_m'] for p in points),
            'largest_recorded_interval_m': max_jump,
            'accuracy_range_m': [min((p['accuracy_m'] for p in points),default=0),
                                 max((p['accuracy_m'] for p in points),default=0)],
            'saved_road_segments': len(roads), 'road_names': sorted(set(r['name'] for r in roads)),
            'route_coverage_within_3_35m': sum(d<=3.35 for d in route_errors)/len(route_errors),
            'turns': corner_results}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk')
    parser.add_argument('--expected-sha256', default='77e8aec1810cc6c6559ca0439e12598648b4af4f496544a3fd01717dad22bf02')
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--plan-only', action='store_true')
    parser.add_argument('--keep-location-on', action='store_true',
                        help='Control replay: keep system GPS on through all corners')
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    plan, at = route_plan()
    (args.out / 'planned-drive.json').write_text(json.dumps(plan, indent=2))
    print('Planned route:', round(plan['distance_m']), 'm;', plan['duration_s'],
          'seconds;', len(plan['turns']), 'turns;', len(plan['rows']), 'fixes', flush=True)
    if args.plan_only:
        return
    assert args.apk
    assert hashlib.sha256(Path(args.apk).read_bytes()).hexdigest() == args.expected_sha256
    assert EMULATOR_SERIAL.startswith('emulator-'), 'Replay requires a disposable emulator'
    print('Using ADB:', ADB, 'device:', EMULATOR_SERIAL, flush=True)
    for attempt in range(3):
        adb('wait-for-device')
        try:
            print(adb('root'), flush=True)
        except RuntimeError as error:
            print('ADB setup attempt', attempt + 1, error, flush=True)
        adb('wait-for-device')
        if adb('shell', 'id', '-u', check=False) == '0':
            break
        time.sleep(2)
    else:
        raise RuntimeError('Disposable emulator root connection did not become ready')
    adb('shell', 'wm', 'size', '1080x2340')
    adb('shell', 'wm', 'density', '420')
    print(adb('install', '-r', args.apk), flush=True)
    for permission in ['ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION',
                       'ACCESS_BACKGROUND_LOCATION', 'POST_NOTIFICATIONS']:
        adb('shell', 'pm', 'grant', PACKAGE, 'android.permission.'+permission, check=False)
    adb('shell', 'settings', 'put', 'secure', 'location_mode', '3')
    prefs = args.out / 'initial-preferences.xml'
    prefs.write_text('''<?xml version="1.0" encoding="utf-8"?><map>
<boolean name="account_prompt_shown" value="true"/>
<boolean name="background_prompt_shown" value="true"/>
<boolean name="location_disclosure_shown" value="true"/>
<boolean name="manual_only" value="false"/>
<boolean name="fog_enabled" value="true"/>
</map>''')
    folder = f'/data/user/0/{PACKAGE}/shared_prefs'
    uid = adb('shell', 'stat', '-c', '%u', f'/data/user/0/{PACKAGE}')
    assert uid.isdigit(), uid
    adb('shell', 'mkdir', '-p', folder)
    adb('push', str(prefs), folder+'/roadconquest_preferences.xml')
    adb('shell', 'chown', '-R', uid+':'+uid, folder)
    adb('shell', 'restorecon', '-R', folder)
    gps = GpsController()
    gps.fix(plan['path'][0], 0, plan['rows'][0]['bearing'])
    adb('logcat', '-c')
    print(adb('shell', 'am', 'start', '-W', '-n', PACKAGE+'/.DefaultLauncher'), flush=True)
    time.sleep(8)
    ui = dump_ui(args.out, 'start-ui')
    # New releases defer automatic tracking until the first-use guide is closed.
    # Use its real Skip control; do not bypass app startup or alter the APK.
    if tap_id(ui, 'tourSkipButton'):
        deadline = time.monotonic() + 10
        while True:
            ui = dump_ui(args.out, 'after-tour-ui')
            if not any(n.get('resource-id') == PACKAGE + ':id/tourSkipButton' for n in ui.iter('node')):
                break
            assert time.monotonic() < deadline, 'First-use guide did not close'
    tap_id(ui, 'centerCarButton')
    screenshot(args.out, 'start')
    locations = adb('shell', 'dumpsys', 'location')
    (args.out / 'location-initial.txt').write_text(locations)
    services = adb('shell', 'dumpsys', 'activity', 'services', PACKAGE)
    deadline = time.monotonic() + 10
    while '.TrackingService' not in services and time.monotonic() < deadline:
        time.sleep(.25)
        services = adb('shell', 'dumpsys', 'activity', 'services', PACKAGE)
    (args.out / 'services-initial.txt').write_text(services)
    print('Tracking service diagnostics:', services, flush=True)
    print('Initial UI:', [(n.get('text'), n.get('resource-id')) for n in ui.iter('node')
                          if n.get('text')], flush=True)
    assert '.TrackingService' in services, 'Tracking service did not start'
    start = time.monotonic()
    plan['start_wall_ms'] = round(time.time() * 1000)
    (args.out / 'planned-drive.json').write_text(json.dumps(plan, indent=2))
    events = []
    background = False
    location_off = False
    off_until = 0
    shown_turns = set()
    try:
        for i, row in enumerate(plan['rows']):
            time.sleep(max(0, start+row['t']-time.monotonic()))
            if 2250 < row['s'] < 2400 and not background:
                adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
                adb('shell', 'input', 'keyevent', 'KEYCODE_SLEEP')
                background = True
                events.append({'t':row['t'], 'wall_ms':round(time.time()*1000),
                               'event':'background-and-screen-off'})
            if background and row['s'] >= 2500:
                adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
                adb('shell', 'wm', 'dismiss-keyguard')
                adb('shell', 'am', 'start', '-n', PACKAGE+'/.DefaultLauncher')
                background = False
                events.append({'t':row['t'], 'wall_ms':round(time.time()*1000),
                               'event':'return-to-app'})
            if not args.keep_location_on and 2900 < row['s'] < 2970 and not location_off and off_until == 0:
                adb('shell', 'settings', 'put', 'secure', 'location_mode', '0')
                location_off = True
                off_until = row['t'] + 12
                events.append({'t':row['t'], 'wall_ms':round(time.time()*1000),
                               'event':'system-location-off-for-12s'})
            if location_off and row['t'] >= off_until:
                adb('shell', 'settings', 'put', 'secure', 'location_mode', '3')
                location_off = False
                events.append({'t':row['t'], 'wall_ms':round(time.time()*1000),
                               'event':'system-location-restored'})
            gps.fix(row['position'], row['speed'], row['bearing'])
            if i == 6:
                location = adb('shell', 'dumpsys', 'location')
                (args.out / 'location-early.txt').write_text(location)
                check_gps_clock(location)
                speed_check = check_native_speed(location, plan['rows'][:i+1])
                (args.out / 'native-speed-check.json').write_text(json.dumps(speed_check, indent=2))
                print('Native GPS speed check:', speed_check, flush=True)
                fixes = re.findall(r'last location=Location\[(?:gps|fused) (-?[\d.]+),(-?[\d.]+)',location)
                assert any(meters(plan['path'][0],[float(lon),float(lat)])>50
                           for lat,lon in fixes), 'Emulator GPS coordinates did not move'
                adb('pull',f'/data/user/0/{PACKAGE}/databases',str(args.out/'early-db'))
                early = sqlite3.connect(args.out/'early-db/roadconquest.db')
                count = early.execute('SELECT COUNT(*) FROM track_points').fetchone()[0]
                early.close()
                print('Early driving storage check:',count,'points',flush=True)
                assert count > 0, 'Emulator fixes did not reach normal driving storage'
            if i % 10 == 0:
                print('Replay', i, '/', len(plan['rows']), row['phase'], round(row['s']), 'm', flush=True)
                assert adb('shell', 'pidof', PACKAGE), 'App died during drive'
            for j, turn in enumerate(plan['turns']):
                if j not in shown_turns and 35 < row['s'] - turn['at_m'] < 75 and not background:
                    tap_id(ui, 'centerCarButton')
                    time.sleep(.8)
                    screenshot(args.out, f'turn-{j+1:02d}-{turn["direction"]}')
                    shown_turns.add(j)
        # Allow normal live matching and partial-turn retries to finish, without forcing matches.
        gps.fix(plan['rows'][-1]['position'], 0, plan['rows'][-1]['bearing'])
        # Only dismiss an observed system Location dialog. An unconditional Back
        # exits the app in the control run and invalidates the final map screenshot.
        if off_until:
            dialog = dump_ui(args.out, 'location-dialog-ui')
            if any(n.get('text') == 'No location access' for n in dialog.iter('node')):
                for node in dialog.iter('node'):
                    if node.get('text') == 'Close':
                        x1,y1,x2,y2 = map(int,re.findall(r'\d+',node.get('bounds')))
                        adb('shell','input','tap',(x1+x2)//2,(y1+y2)//2)
                        break
        print('Waiting 60 seconds for normal matching retries', flush=True)
        time.sleep(60)
        tap_id(ui, 'centerCarButton')
        time.sleep(.8)
        screenshot(args.out, 'finished-live')
        final_ui = dump_ui(args.out, 'finished-ui')
        assert any(n.get('package') == PACKAGE for n in final_ui.iter('node')), 'Final screenshot is not the app'
        logs = adb('logcat', '-d')
        (args.out / 'logcat.txt').write_text(logs)
        (args.out / 'location-final.txt').write_text(adb('shell','dumpsys','location'))
        (args.out / 'frame-stats.txt').write_text(adb('shell','dumpsys','gfxinfo',PACKAGE,'framestats'))
        (args.out / 'events.json').write_text(json.dumps(events,indent=2))
        assert 'Process: '+PACKAGE+',' not in logs, 'AndroidRuntime crash during replay'
        assert adb('shell', 'pidof', PACKAGE), 'App is not alive after drive'
        points, roads = snapshot(args.out)
        (args.out / 'recorded-points.json').write_text(json.dumps(points,indent=2))
        (args.out / 'matched-roads.json').write_text(json.dumps(roads,indent=2))
        metrics = analyze(plan, at, points, roads)
        (args.out / 'metrics.json').write_text(json.dumps(metrics,indent=2))
        print(json.dumps(metrics,indent=2), flush=True)
        assert len(points) >= 50, 'GNSS replay did not reach normal driving storage'
        assert roads, 'The live matcher did not persist any road geometry'
        assert metrics['recorded_distance_m'] < plan['distance_m'] * 1.2, 'Replay inflated mileage'
    finally:
        (args.out / 'diagnostics.txt').write_text(adb('logcat','-d','-s','AndroidRuntime:E','RoadConquest:E'))
        screenshot(args.out, 'last-screen')


if __name__ == '__main__':
    main()
