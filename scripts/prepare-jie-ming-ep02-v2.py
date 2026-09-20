"""Prepare the approved second-episode revision; preserve every older render."""
import json
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'output/jie-ming-ep02-v2'
OLD = ROOT / 'output/jie-ming-ep02'


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def main():
    if (OUT / 'manifest.json').exists():
        raise FileExistsError('Already prepared; never reset existing take identities')
    for name in ('keyframes', 'clips', 'audio'):
        (OUT / name).mkdir(parents=True, exist_ok=True)
    for image in (OLD / 'keyframes').glob('*.png'):
        shutil.copy2(image, OUT / 'keyframes' / image.name)
    for name in ('pei-tight.png', 'shen-standing.png'):
        shutil.copy2(ROOT / 'output/jie-ming-ep01-v2/keyframes' / name,
                     OUT / 'keyframes' / name)
    script_path = 'screenplays/jie-ming-bu-huan/revisions/opening-pair-v2/episode-02.txt'
    script = (ROOT / script_path).read_text(encoding='utf-8')
    dialogue = re.findall(r'^(裴衡|沈照|岑岳)：(.+)$', script, re.M)
    specs = [
        ('02-return-paper-v1', 'pei-tight.png', 260,
         'He wants her to return the document and sit down, presenting it as an ordinary rule. Looks offscreen LEFT.'),
        ('03-ask-why-v1', 'shen-standing.png', 175,
         'She stands facing her partner offscreen RIGHT, asking why he objects to asking their own master. She keeps the folded paper in her right hand below frame.'),
        ('04-offer-to-ask-v1', 'pei-tight.png', 209,
         'He tries to take over the errand so she will give him the paper, a slightly impatient practical question. Looks offscreen LEFT.'),
        ('06-call-clerk-v1', 'shen-window-out.png', 192,
         'She addresses the clerk a few steps outside the LEFT window, politely projecting a clear question, not shouting.'),
        ('08-master-admits-v1', 'master-window.png', 277,
         'The older man acknowledges his own seal and dismisses the concern as needless fuss, ordinary mature conversational voice. Looks at his disciple offscreen LEFT.'),
        ('09-ask-liability-v1', 'shen-window-in.png', 226,
         'She asks her master offscreen RIGHT for a concrete answer: who is struck by the lightning. Respectful but persistent, no heroic declamation.'),
        ('10-master-reassures-v1', 'master-window.png', 141,
         'He reassures his disciple offscreen LEFT to stop her questioning, an ordinary familiar response, not a sinister whisper.'),
        ('11-write-it-v1', 'shen-window-in.png', 243,
         'She asks her master offscreen RIGHT to put his reassurance in writing. Calm precise wording, a brief natural punctuation pause, no triumphant smile.'),
        ('13-file-order-v1', 'master-window.png', 243,
         'He directs the clerk offscreen LEFT to file the unsigned document with earlier ones. A routine instruction, not announcing a secret, do not overemphasize 前几张.'),
        ('14-register-v1', 'shen-window-out.png', 175,
         'She addresses the clerk outside the LEFT window to record that she has not signed and will return tomorrow. Polite and practical.')]
    assert len(dialogue) == len(specs)
    takes = []
    for i, ((speaker, line), (key, image, frames, direction)) in enumerate(zip(dialogue, specs)):
        takes.append({'id': key, 'image': image, 'frames': frames, 'seed': 2026092020+i,
            'mode': 'dialogue', 'lines': [{'speaker': speaker, 'text': line}],
            'prompt': 'One locked head-and-shoulders live-action period-drama close-up of this exact person in the same robe and daylight study. '
                + direction + ' The person promptly says in clear connected natural Mandarin: "' + line
                + '". Keep the exact face, hair, clothing and lighting. Hands, paper and other people stay outside this close-up. '
                'Natural conversational pace, no drawn-out syllables or long waits. One continuous real-time take, restrained face and small natural lip movement. '
                'Close the mouth after the sentence. Clean footage: no written overlays, subtitles, narrator, music, inserts or cuts.'})
    takes.extend([
        {'id':'01-door-continuation-v1','image':'continuity-start.png','frames':260,'seed':2026092040,'mode':'narration','lines':[],
         'prompt':'One continuous real-time live-action medium-wide shot in the same daylight wooden study. The teal-robed man at the RIGHT doorway immediately lowers his raised arm, turns left toward the crimson-robed woman and holds out his empty right palm asking for the paper. She keeps her single folded ivory paper in her RIGHT hand near her waist and does not give it to him. She glances at his outstretched hand and back at him. Both stay in the room, with the woman LEFT and the man RIGHT. Preserve their exact faces, robes and hairstyles. Camera stays south of them, left window and right door unchanged. Ordinary connected movement, no repeated gestures or slow motion. Their mouths stay closed; quiet footsteps and fabric only, no voice, narration, music, captions or cuts.'},
        {'id':'05-to-window-v3','image':'window-move.png','frames':209,'seed':2026092041,'mode':'narration','lines':[],
         'prompt':'One continuous real-time live-action medium-wide take, camera fixed. The crimson-robed woman immediately walks two ordinary steps LEFT to the open window, takes her single folded ivory contract from her RIGHT hand and opens it on the wooden ledge with the red stamp facing up. She keeps her right fingers resting on the near paper corner, NEVER releases the paper. Her left hand lowers. The teal-robed man follows just one step then stops inside the room, leaving her access to the window. Keep the same faces, plain crimson and teal robes, window, door and daylight. Complete the movement without posing or slow motion. Closed mouths, only footsteps and paper rustle. No speech, narration, music, cuts, captions or extra paper.'},
        {'id':'12-master-silent-v1','image':'master-window.png','frames':124,'seed':2026092042,'mode':'narration','lines':[],
         'prompt':'One fixed live-action close-up of this exact older master in gray robes. He looks down once at the brush offered below frame, then looks back to his disciple offscreen LEFT, choosing not to accept it. Small ordinary eye and chin movement, restrained neutral face, mouth naturally closed throughout. Hands stay outside frame. Same identity, robe, left daylight and study. Real time, no slow motion, no cuts, no speech, sighs, narration, music, written text or subtitles.'},
        {'id':'15-record-v1','image':'clerk-record.png','frames':158,'seed':2026092043,'mode':'narration','lines':[],
         'prompt':'A single continuous close insert at normal speed. Only the registry clerk\'s slate-blue sleeves and hands, the open cream register on the left and the single red-sealed unsigned contract at right on this wooden window ledge. With the brush in his right hand he writes one short practical entry on the open register, then lifts the brush clear. Do not write or sign on the separate red-sealed contract. Keep paper and objects distinct and stationary. Natural hand anatomy, no multiplying fingers or sheets. Fixed camera, stable daylight. Paper and brush sounds only, no voice, narrator, music, readable text overlays or cuts.'}
    ])
    prior = json.loads((OLD/'manifest.json').read_text(encoding='utf-8'))
    reuse_ids = ['05-master-arrives-v1','09-offer-pen-v1','10-file-paper-v1','12-follow-file-v1','07-no-mention-v1']
    for take in prior['takes']:
        if take['id'] in reuse_ids:
            shutil.copytree(OLD/'clips'/take['id'], OUT/'clips'/take['id'])
            takes.append({**take,'reuseFrom':f'output/jie-ming-ep02/clips/{take["id"]}'})
    narration = re.findall(r'^旁白／沈照：(.+)$', script, re.M)
    clauses = [s for block in narration for s in re.findall(r'[^，。？！]+[，。？！]?', block)]
    (OUT/'audio/narration.txt').write_text('\n'.join(clauses)+'\n', encoding='utf-8')
    save(OUT/'audio/narration-manifest.json', {'voice':[{'shot':1,'output':'narration-master.wav','speaker':'Serena',
        'text':''.join(narration), 'temperature':0.6,'topP':0.8,'repetitionPenalty':1.1,
        'instruct':'年轻女性普通话，向听众讲述自己正在经历的事，语速利落，语句连贯，句间短暂停顿，不耳语。开头交代为什么不能交出借条。提到师父没来救她时不哭不叹气，接着把注意力转回眼前。最后听见前几张时心里起疑，想去找其他借牌的人问清楚，不故作悬念。照正文读，不加词，不拖字，不用播音腔。'}]})
    counts = [len(re.findall(r'[^，。？！]+[，。？！]?', block)) for block in narration]
    blocks=[]
    position=0
    picture_ids=[['01-door-continuation-v1'],['05-to-window-v3'],['09-offer-pen-v1','12-master-silent-v1'],['15-record-v1','10-file-paper-v1','12-follow-file-v1']]
    for i,count in enumerate(counts):
        blocks.append({'id':f'N{i+1}','segments':list(range(position,position+count)), 'takes':picture_ids[i]})
        position+=count
    manifest={'title':'借命不还','episode':2,'episodeTitle':'纸留下','revision':'opening-pair-v2',
        'source':'original','format':'novel-narration-with-native-dialogue','stage':'in_production',
        'approvedScript':script_path,'narrator':'Shen Zhao first person; Qwen3-TTS Serena single master',
        'narrationMaster':'audio/narration-master.wav','takes':takes,'narrationBlocks':blocks,
        'nativeAmbienceTakes':[t['id'] for t in takes if t['mode']=='narration'],
        'ambientBlocks':[{'id':'B1','take':'05-master-arrives-v1','inFrame':0,'outFrame':88}],
        'editOrder':['N1','02-return-paper-v1','03-ask-why-v1','04-offer-to-ask-v1','N2','06-call-clerk-v1','B1',
                     '08-master-admits-v1','09-ask-liability-v1','10-master-reassures-v1','11-write-it-v1','N3',
                     '13-file-order-v1','14-register-v1','N4'],
        'artisticApproval':False,'publicationApproval':False}
    save(OUT/'manifest.json',manifest)
    save(OUT/'edit-overrides.json',{'narrationPictures':{}})
    print('Prepared', OUT, 'new takes',len(takes)-len(reuse_ids), 'narration clauses',counts)


if __name__ == '__main__':
    main()
