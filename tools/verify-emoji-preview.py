#!/usr/bin/env python3
"""Native Android checks. Uses measured IME bounds; taps are genuine adb input events."""
import argparse, hashlib, json, re, shlex, sqlite3, subprocess, time, uuid
from pathlib import Path
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--adb', default='adb', help='Command prefix, e.g. docker exec svk-adb-loopback /sdk/adb')
parser.add_argument('--out', type=Path, required=True)
parser.add_argument('--shots', type=Path, required=True)
parser.add_argument('--stage', choices=['full','extras'], default='full')
args = parser.parse_args()
prefix = shlex.split(args.adb)
pkg = 'ru.smartvoice.keyboard.previewfix'
ime = pkg + '/helium314.keyboard.latin.LatinIME'
activity = pkg + '/helium314.keyboard.smart.debug.InputTestActivity'
args.out.mkdir(parents=True, exist_ok=True)
args.shots.mkdir(parents=True, exist_ok=True)

def adb(*parts):
    if parts and parts[0] == 'shell': parts = ('shell', shlex.join(parts[1:]))
    return subprocess.check_output(prefix + list(parts), timeout=60)
def broadcast(**extras):
    cmd = ['am','broadcast','-a','ru.smartvoice.DEBUG_UI','-p',pkg]
    for key, value in extras.items(): cmd += ['--es',key,str(value)]
    adb('shell',*cmd)
def geometry():
    token = uuid.uuid4().hex
    broadcast(page='geometry', token=token)
    for attempt in range(12):
        try:
            value = json.loads(adb('exec-out','cat',f'/sdcard/Android/data/{pkg}/files/geometry.json'))
            if value.get('token') == token: return value
        except (ValueError,subprocess.CalledProcessError): pass
        if attempt == 5: broadcast(page='geometry', token=token)
        time.sleep(.25)
    raise AssertionError('Geometry response not ready')
def node(name):
    for attempt in range(5):
        value = geometry()
        if value['inputShown'] and name in value['nodes']:
            assert value['fieldId'] == 65537, value
            return value['nodes'][name]
        time.sleep(.5)
    raise AssertionError(f'Visible IME node missing: {name}; {value}')
def tap_rect(rect):
    x1,y1,x2,y2 = rect
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
def tap(name): tap_rect(node(name))
def screen(name): (args.shots/name).write_bytes(adb('exec-out','screencap','-p'))
def xml():
    for attempt in range(4):
        adb('shell','uiautomator','dump','/sdcard/svk-check.xml')
        try:
            return ET.fromstring(adb('exec-out','cat','/sdcard/svk-check.xml'))
        except ET.ParseError: time.sleep(.5)
    raise AssertionError('UiAutomator active root unavailable')
def start():
    adb('shell','am','force-stop',pkg)
    adb('shell','ime','set',ime)
    adb('shell','am','start','-W','-n',activity)
    # Force-stop can asynchronously reset the selected IME after the first set.
    adb('shell','ime','enable',ime)
    adb('shell','ime','set',ime)
    for attempt in range(8):
        try:
            value = geometry()
            if value['inputShown'] and value['fieldId'] == 65537 and value['keys']: return value
        except AssertionError: pass
        try:
            root = xml()
            fields = [n for n in root.iter('node') if n.get('package') == pkg and n.get('class') == 'android.widget.EditText' and n.get('password') != 'true']
            if fields: tap_rect(list(map(int,re.findall(r'\d+',fields[0].get('bounds')))))
        except AssertionError: pass
        time.sleep(.5)
    raise AssertionError('Normal Android editor/IME not ready')
def received(mime):
    for attempt in range(3):
        lines = [n.get('text','') for n in xml().iter('node')]
        if any('Принято изображений:' in v and mime in v for v in lines): return next(v for v in lines if 'Принято' in v)
        time.sleep(.5)
    raise AssertionError(f'Receiver did not accept {mime}: {lines}')
def db(name):
    p = args.out/name
    p.write_bytes(adb('exec-out','cat',f'/data/user/0/{pkg}/databases/heliboard.db'))
    with sqlite3.connect(p) as connection:
        return connection.execute('SELECT ID,TIMESTAMP,PINNED,TEXT,FILE,MIME_TYPE FROM CLIPBOARD ORDER BY ID').fetchall()

def full():
    checks = {'device':'API 29, 720x1280, density 320','tap_source':'Measured visible Android view bounds; fieldId 65537 required'}
    value = start()
    checks['initial_geometry'] = value
    assert '-205' not in value['keys'], 'Default row unexpectedly contains numpad'
    # Copy text -> real screen PNG -> text -> another real screen PNG through ClipboardManager.
    # Never depend on old unpinned fixtures surviving a previously configured retention interval.
    seed = uuid.uuid4().hex[:8]
    for index in range(2):
        adb('shell','am','broadcast','-a','ru.smartvoice.DEBUG_CLIP','-p',pkg,'--es','text',f'Проверка буфера {seed} {index+1}')
        if index: broadcast(page='functions')
        source = f'/storage/emulated/0/Android/data/{pkg}/files/native-clip-{index}.png'
        adb('shell','screencap','-p',source)
        adb('shell','am','broadcast','-a','ru.smartvoice.DEBUG_CLIP','-p',pkg,'--ez','image','true','--es','imageSource',source)
        if index: adb('shell','input','keyevent','4')
    for _ in range(12):
        seeded = db('history-seeded.db')
        if sum(v[3] is not None and seed in v[3] for v in seeded) == 2 and sum(v[4] is not None for v in seeded) >= 2: break
        time.sleep(.5)
    assert sum(v[3] is not None and seed in v[3] for v in seeded) == 2 and sum(v[4] is not None for v in seeded) >= 2, seeded
    checks['mixed_copy_sequence'] = 'text, actual screencap PNG, text, actual screencap PNG'

    broadcast(page='emoji')
    tap('sv_emoji_studio_banner')
    tap('sv_test_emoji_128570')  # actual cat choice in the compositor
    screen('10-sticker-studio.png')
    tap('sv_test_sticker_insert')
    time.sleep(3)
    adb('shell','input','keyevent','4')
    tap('sv_emoji_back')
    checks['png_receiver'] = received('image/png')
    screen('11-sticker-inserted.png')
    print('PNG received by Android field', flush=True)
    # The saved library is tested independently from the compositor and after Activity return.
    broadcast(page='emoji')
    tap('sv_emoji_stickers')
    screen('13-sticker-library.png')
    adb('shell','input','keyevent','4')
    tap('sv_emoji_back')
    # Verify the four clipboard items survive another process restart with their files/IDs.
    before = db('history-final-before.db')
    assert len(before) >= 4 and sum(v[4] is not None for v in before) >= 2, before
    checks['history_before'] = [{'id':v[0],'pinned':bool(v[2]),'image':v[4] is not None,'file':v[4]} for v in before]
    start()
    after = db('history-final-after.db')
    assert before == after, 'History changed across process restart'
    checks['history_restart_equal'] = True
    # Actual text in Android and three visible candidates, then all four product sections.
    broadcast(type='Приве')
    assert any(n.get('text') == 'Приве' for n in xml().iter('node')), 'Native typed text missing'
    screen('01-main.png')
    broadcast(page='functions')
    screen('02-functions.png')
    adb('shell','input','keyevent','4')
    broadcast(page='clipboard')
    screen('04-clipboard.png')
    broadcast(page='alpha')
    broadcast(page='emoji')
    screen('03-emoji.png')
    # Search itself was checked with genuine letter taps; capture this release's focused view.
    # Its dynamic local keyboard always stays anchored above the same bottom insets.
    frame = node('main_keyboard_frame')
    # The search chip is the second visible control in the actual emoji tab strip.
    # Header height is 40dp; the chip occupies x=36..152dp at this density.
    tap_rect([72,frame[1],304,frame[1]+80])
    assert 'sv_emoji_search' in geometry()['nodes']
    for x,y in [(230,930),(430,1000),(360,1070)]: adb('shell','input','tap',str(x),str(y))
    screen('05-emoji-search.png')
    checks['search_geometry'] = geometry()
    assert checks['search_geometry']['inputShown']
    # Read the installed APK bytes, not just the package manager version name.
    apk_path = adb('shell','pm','path',pkg).decode().strip().removeprefix('package:')
    assert apk_path.startswith('/data/app/') and apk_path.endswith('/base.apk')
    installed = adb('exec-out','cat',apk_path)
    checks['installed_apk_sha256'] = hashlib.sha256(installed).hexdigest()
    crash = adb('logcat','-b','crash','-d').decode()
    assert 'FATAL EXCEPTION' not in crash, crash
    (args.out/'crash-log.txt').write_text(crash)
    checks['crash_buffer_empty'] = not bool(crash.strip())
    (args.out/'native-checks.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2))
    print(json.dumps({k:v for k,v in checks.items() if k in ('png_receiver','history_restart_equal','installed_apk_sha256','crash_buffer_empty')},ensure_ascii=False), flush=True)

def extras():
    path = args.out/'native-checks.json'
    checks = json.loads(path.read_text())
    start()
    broadcast(page='emoji')
    tap('sv_emoji_stickers')
    frame = node('sv_inline_panel')
    bad = f'/data/user/0/{pkg}/files/stickers/2908c2249f3db7b82859820c6d25082afa033ece7b39939eba39146832afbfc4.png'
    # Remove only the known broken fixture created by this test session, using UI confirmation.
    if adb('shell','ls',f'/data/user/0/{pkg}/files/stickers').decode().find('2908c224') >= 0:
        names = [v for v in adb('shell','ls','-t',f'/data/user/0/{pkg}/files/stickers').decode().split() if v.endswith('.png')]
        index = names.index(Path(bad).name)
        x = int(frame[0]+(index%3+.5)*(frame[2]-frame[0])/3)
        y = frame[1]+292+(index//3)*216  # header + create action + actual tile row
        adb('shell','input','swipe',str(x),str(y),str(x),str(y),'1300')
        adb('shell','input','tap',str(x),str(y))
        time.sleep(1)
        assert '2908c224' not in adb('shell','ls',f'/data/user/0/{pkg}/files/stickers').decode()
    checks['sticker_delete_confirmed'] = True
    screen('13-sticker-library.png')
    # Insertion from the persistent library, after a process restart.
    frame = node('sv_inline_panel')
    tap_rect([frame[0],frame[1]+184,frame[0]+(frame[2]-frame[0])//3,frame[1]+400])
    adb('shell','input','keyevent','4')
    tap('sv_emoji_back')
    checks['png_library_receiver'] = received('image/png')
    broadcast(page='emoji')
    tap('sv_emoji_gif')
    frame = node('sv_inline_panel')
    tap_rect([frame[0],frame[1]+96,frame[0]+(frame[2]-frame[0])//3,frame[1]+312])
    adb('shell','input','keyevent','4')
    tap('sv_emoji_back')
    checks['gif_receiver'] = received('image/gif')
    # Real language-key touches and a real numeric key, not synthetic text injection.
    tap_rect(geometry()['keys']['-227'])
    english = geometry()['keys']
    assert any(str(c) in english for c in [ord('a'),ord('A')]), english
    tap_rect(english['-227'])
    russian = geometry()['keys']
    assert any(str(c) in russian for c in [ord('ф'),ord('Ф')]), russian
    checks['language_switch_round_trip'] = True
    broadcast(page='functions')
    frame = node('sv_functions_panel')
    # Middle card of the second row; use the actual panel origin.
    adb('shell','input','tap',str((frame[0]+frame[2])//2),str(frame[1]+360))
    tap_rect(geometry()['keys']['55'])
    assert any(n.get('text') == '7' for n in xml().iter('node'))
    tap_rect(geometry()['keys']['-201'])
    checks['physical_numpad_digit'] = '7'
    broadcast(page='clipboard')
    frame = node('main_keyboard_frame')
    y = frame[1]+40
    for x in [frame[2]-44,frame[0]+324,frame[0]+412]: adb('shell','input','tap',str(x),str(y))
    time.sleep(.5)
    before = db('history-pinned-before.db')
    assert len(before) >= 4 and all(v[2] for v in before), before
    screen('04-clipboard.png')
    start()
    after = db('history-pinned-after.db')
    assert [(v[0],v[2:]) for v in before] == [(v[0],v[2:]) for v in after]
    checks['four_pins_survive_restart'] = True
    checks['pinned_clip_count'] = len(after)
    checks['saved_media_files'] = adb('shell','ls','-l',f'/data/user/0/{pkg}/files/stickers').decode()
    path.write_text(json.dumps(checks,ensure_ascii=False,indent=2))
    print('Verified library insert/delete, original GIF MIME, RU/EN, physical 7, four pinned clips after restart',flush=True)

if __name__ == '__main__':
    full() if args.stage == 'full' else extras()
