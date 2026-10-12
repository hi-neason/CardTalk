"""Create original SVG pictograms and local fixed-word audio for development.

SVG drawings are repository-owned. Audio uses an available macOS system voice;
review voice redistribution terms before shipping a commercial content pack.
No external AI service or personal recording is used.
"""
from pathlib import Path
import argparse
import re
import subprocess
import html

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--audio', action='store_true')
args = parser.parse_args()
source = (ROOT / 'frontend/src/domain/content.ts').read_text()
pairs = re.findall(r"\['([a-z-]+)','([^']+)'(?:,'[^']+')?\]", source)
icons = dict(pairs)
drawings = {
 'apple':'<path d="M35 23c-15-10-28 5-20 24 7 17 16 15 21 12 7 5 17 1 22-12 9-20-6-31-20-24" fill="#eaa49b"/><path d="M36 23V13c7-6 12-6 17-5-2 9-8 13-17 12" fill="#7baf8f"/>',
 'banana':'<path d="M18 16c6 24 24 30 40 16-4 23-26 35-40 12-6-9-8-19-7-27z" fill="#efd088"/><path d="M18 17 13 10m45 22 4-4"/>',
 'orange':'<circle cx="35" cy="39" r="23" fill="#edbd83"/><path d="M36 16c3-9 12-10 17-8-3 8-10 9-17 8M26 35h1m21 10h1"/>',
 'grape':'<path d="M35 17V7m0 4c7-5 13-4 16-1"/><g fill="#b9a0ce"><circle cx="25" cy="27" r="10"/><circle cx="45" cy="27" r="10"/><circle cx="20" cy="43" r="10"/><circle cx="38" cy="42" r="10"/><circle cx="53" cy="43" r="10"/><circle cx="32" cy="57" r="10"/><circle cx="47" cy="57" r="9"/></g>',
 'watermelon':'<path d="M9 27h54c-1 37-52 37-54 0z" fill="#b8d2b6"/><path d="M15 27h42c-1 27-39 27-42 0z" fill="#e8a59e"/><path d="M25 35v3m11 3v3m11-9v3"/>',
 'strawberry':'<path d="M17 24c-7 19 10 39 19 40 10-3 25-24 19-40z" fill="#e6a2a4"/><path d="m17 23 13-3 6-9 6 9 13 3-12 7-7-4-7 4z" fill="#a1bda0"/><path d="M26 37v2m18-2v2m-10 9v2"/>',
 'water':'<path d="M36 9S17 29 17 42a19 19 0 0 0 38 0C55 29 36 9 36 9z" fill="#b2d5e4"/><path d="M25 43a11 11 0 0 0 11 11"/>',
 'rice':'<path d="M13 34c2-20 43-20 46 0" fill="#f5ecd5"/><path d="M10 34h52c0 16-10 26-26 26S10 50 10 34z" fill="#c4d4e4"/><path d="M23 26h3m9-3h3m8 4h3"/>',
 'noodles':'<path d="M12 33h49c-3 23-44 23-49 0z" fill="#c7d5e5"/><path d="M24 31c-9-18 7-18 2-28m10 28c9-20-5-16-1-28M44 31c-7-18 8-20 6-28M12 25l42-6"/>',
 'bread':'<path d="M20 16c-8 0-11 9-6 15v26h44V31c8-10-2-18-11-15-7-10-21-7-27 0z" fill="#e6c79d"/><path d="M22 37v12m13-12v12m13-12v12"/>',
 'milk':'<path d="m22 20 8-12h19l6 12v43H22z" fill="#d1dfed"/><path d="M22 20h33M42 20l7-12m-7 12v43M28 35h9m-9 9h9"/>',
 'egg':'<path d="M36 10c-9 0-23 25-23 37 0 21 46 21 46 0 0-12-14-37-23-37z" fill="#ede1c8"/><path d="M24 47c0 8 5 11 11 11"/>',
 'bus':'<rect x="15" y="10" width="43" height="48" rx="9" fill="#b9d5de"/><path d="M15 36h43M36 16v18m-16-9h32M23 58v7m26-7v7"/><circle cx="24" cy="47" r="3"/><circle cx="49" cy="47" r="3"/>',
 'car':'<path d="m14 32 7-18h29l8 18v25H14z" fill="#bdd2e2"/><path d="M14 33h44M23 21h25M20 57v7m32-7v7"/><circle cx="24" cy="44" r="3"/><circle cx="48" cy="44" r="3"/>',
 'bike':'<circle cx="15" cy="48" r="11" fill="#d3e4e5"/><circle cx="58" cy="48" r="11" fill="#d3e4e5"/><path d="m15 48 14-24 17 24H15l17-19h18l8 19M46 18h8m-29 0h10"/>',
 'train':'<rect x="18" y="8" width="37" height="47" rx="10" fill="#c2d7de"/><path d="M18 33h37m-30-18h24v13H25zM27 55l-9 11m28-11 9 11M23 61h27"/><circle cx="27" cy="43" r="3"/><circle cx="46" cy="43" r="3"/>',
 'bed':'<path d="M10 20v43m52-34v34M10 50h52M11 29h18v19H11m18-18h24c7 0 9 4 9 9v11H29" fill="#d8e0ec"/>',
 'book':'<path d="M36 18c-9-7-19-7-29-5v43c10-2 20-1 29 6 9-7 19-8 29-6V13c-10-2-20-2-29 5z" fill="#d4dfed"/><path d="M36 18v44M16 25h11m-11 9h11m18-9h11m-11 9h11"/>',
 'clothes':'<path d="m24 10-18 9 8 19 9-6v31h25V32l10 6 8-19-18-9c-3 11-21 11-24 0z" fill="#d2d9e9"/>',
 'phone':'<rect x="21" y="8" width="30" height="55" rx="6" fill="#d2deeb"/><path d="M29 15h14m-9 40h5"/>',
 'home':'<path d="m7 32 29-23 29 23M15 27v36h42V27M29 63V41h14v22" fill="#d8dfe8"/>',
 'ball':'<circle cx="36" cy="36" r="26" fill="#cddbea"/><path d="m29 27 13-1 9 13-10 12-14-5-3-12zM36 10l-7 17m13-1 15-9m-6 22 10 7M41 51l2 10m-16-15-14 4"/>',
 'toilet':'<path d="M20 9h19v29H20zM13 38h48c0 17-12 22-24 22H23c-8-4-10-13-10-22zM36 60v6H22v-6" fill="#d4e1e8"/>',
 'help':'<path d="m9 47 15-14 15 5 13-7c8-4 15 4 6 11L40 59H18M7 38l11 23-9 4M43 9v16m-8-8h16" fill="#d0ddeb"/>',
 'pain':'<path d="M40 5 19 39h18l-3 28 22-39H38z" fill="#e9b2a3"/>',
 'again':'<path d="M56 29A24 24 0 1 0 58 46M56 12v17H39"/>',
 'not':'<circle cx="36" cy="36" r="26" fill="#edd3cc"/><path d="m18 18 36 36"/>',
 'yes':'<circle cx="36" cy="36" r="26" fill="#c9dfd0"/><path d="m21 36 10 11 21-24"/>',
 'no':'<circle cx="36" cy="36" r="26" fill="#d9dfeb"/><path d="m26 26 20 20m0-20L26 46"/>',
 'stop':'<path d="M23 36V15a4 4 0 0 1 8 0v19-24a4 4 0 0 1 8 0v24-21a4 4 0 0 1 8 0v22-15a4 4 0 0 1 8 0v25c0 16-8 22-22 22-9 0-14-8-21-18l-6-9c-3-5 3-9 7-5l10 9" fill="#edc9bc"/>',
 'go':'<path d="M12 36h47m-18-19 19 19-19 19"/>',
 'outside':'<path d="M14 63V12h26v51M24 38h1M41 39h24m-10-9 10 9-10 9"/><path d="M14 12 29 7v56" fill="#d6e0eb"/>',
 'look':'<path d="M7 36c15-25 43-25 58 0-15 25-43 25-58 0z" fill="#d9e4ef"/><circle cx="36" cy="36" r="9" fill="#7794b3"/>',
 'listen':'<path d="M18 28c0-27 39-24 39-1 0 18-20 16-20 30-1 12-20 10-20-2m13-25c0-15 18-15 18-1 0 10-11 9-11 16"/>',
 'want':'<path d="M18 38c-14-17 10-29 18-14 8-15 32-3 18 14L36 56z" fill="#d7cce6"/><path d="M36 7v5m-22-1 4 5m40-5-4 5"/>',
 'eat':'<path d="M13 7v20m7-20v20m-14-20v15a14 14 0 0 0 28 0V7M20 36v29M57 7c-13 7-13 21-13 29h13V7zm0 29v29"/>',
 'drink':'<path d="M17 24h38l-4 37H22z" fill="#c8dce8"/><path d="m34 24 8-16h13M21 40h30"/>',
 'walk':'<circle cx="42" cy="13" r="6" fill="#b7cbe1"/><path d="m35 27-8 18 13 9 4 13M27 45 14 65M13 36l15-12 13 4 8 13h15"/>',
 'play':'<path d="m36 9 26 15v29L36 68 10 53V24z" fill="#d6e0ef"/><path d="M36 39v29M10 24l26 15 26-15M22 17l27 16"/>',
 'rest':'<path d="M12 46c0-26 48-26 48 0v16H12z" fill="#d3deeb"/><path d="M10 51h52M20 12h8l-8 9h8m11-17h14l-14 14h14"/>',
 'cold':'<path d="M36 8v56M12 22l48 28M12 50l48-28M27 11l9 9 9-9M27 61l9-9 9 9M11 32l15-4-4-14m39 26-15 4 4 14M11 40l15 4-4 14m39-26-15-4 4-14"/>',
 'hot':'<path d="M43 41V13a7 7 0 0 0-14 0v28a15 15 0 1 0 14 0z" fill="#efd6b3"/><path d="M36 23v28m17-34h11m-11 12h7"/>',
 'more':'<path d="M36 14v44M14 36h44"/>',
 'less':'<path d="M14 36h44"/>',
 'now':'<circle cx="36" cy="36" r="26" fill="#d6e2ec"/><path d="M36 19v20l14 7"/>',
 'later':'<path d="M14 9h44M14 63h44M19 10c0 15 9 18 17 26-8 8-17 12-17 26m34-52c0 15-9 18-17 26 8 8 17 12 17 26"/><path d="M24 57h24L36 43z" fill="#d7e3ee"/>',
 'this':'<path d="M16 35h41m-13-13 13 13-13 13"/><circle cx="15" cy="35" r="7" fill="#b6d0e5"/>',
 'that':'<path d="M14 35h44m-12-12 12 12-12 12"/><circle cx="60" cy="35" r="6" fill="#b6d0e5"/>',
}
for icon, label in icons.items():
 if icon.startswith('core-'): continue
 if icon in ['i','you','mom','dad','grandma','grandpa','teacher','friend']:
  tag={'i':'我','you':'你','mom':'妈','dad':'爸','grandma':'奶','grandpa':'爷','teacher':'师','friend':'友'}[icon]
  drawings[icon]=f'<circle cx="36" cy="24" r="13" fill="#e9d3bd"/><path d="M12 64v-9c0-22 48-22 48 0v9z" fill="#c4d5e8"/><text x="36" y="60" text-anchor="middle" font-size="19" fill="#355578" stroke="none" font-family="sans-serif">{tag}</text>'
 if icon in ['happy','sad','angry','afraid','tired','hungry','thirsty']:
  mouth={'happy':'M23 41c5 10 21 10 26 0','sad':'M24 48c5-10 19-10 24 0','angry':'M24 48h24M21 23l8 4m14 0 8-4','afraid':'M30 43a6 7 0 1 0 12 0a6 7 0 1 0-12 0','tired':'M20 29h10m12 0h10M27 47h18','hungry':'M25 43c5 5 17 5 22 0M36 58v5','thirsty':'M27 44h18M53 44c-7 9-7 14 0 14s7-5 0-14'}[icon]
  drawings[icon]=f'<circle cx="36" cy="36" r="26" fill="#ecd9d8"/><path d="M24 29h1m22 0h1{mouth}"/>'

out=ROOT/'frontend/src/static/cards';out.mkdir(parents=True,exist_ok=True)
for icon in icons:
 if icon.startswith('core-'): continue
 content=drawings.get(icon,'<circle cx="36" cy="36" r="25" fill="#d5dfed"/><path d="M24 36h24M36 24v24"/>')
 svg=f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 72 72"><g fill="none" stroke="#365373" stroke-width="2.7" stroke-linecap="round" stroke-linejoin="round">{content}</g></svg>'
 (out/f'{icon}.svg').write_text(svg)
print(f'Created {len(list(out.glob("*.svg")))} original SVG assets.')
if args.audio:
 audio=ROOT/'frontend/src/static/audio';audio.mkdir(parents=True,exist_ok=True)
 raw=ROOT/'.omx/audio-raw';raw.mkdir(parents=True,exist_ok=True)
 for icon,label in icons.items():
  target=audio/f'{icon}.m4a'
  if target.exists(): continue
  aiff=raw/f'{icon}.aiff'
  subprocess.run(['say','-v','Tingting','-r','150','-o',str(aiff),label],check=True)
  subprocess.run(['afconvert','-f','m4af','-d','aac','-b','32000',str(aiff),str(target)],check=True)
 print(f'Created {len(list(audio.glob("*.m4a")))} fixed-expression audio clips.')
