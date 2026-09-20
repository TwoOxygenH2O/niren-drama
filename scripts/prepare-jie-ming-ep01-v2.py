"""Prepare the approved revision without overwriting earlier episodes or takes."""
from pathlib import Path
import json
import shutil

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'output/jie-ming-ep01-v2'
OLD = ROOT / 'output/jie-ming-ep01'


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def main():
    if (OUT/'manifest.json').exists():
        raise FileExistsError('Prepared already; edit a new take ID instead of resetting state')
    (OUT/'keyframes').mkdir(parents=True, exist_ok=True)
    (OUT/'clips').mkdir(exist_ok=True)
    for image in (OLD/'keyframes').glob('*.png'):
        shutil.copy2(image, OUT/'keyframes'/image.name)
    shutil.copy2(ROOT/'output/jie-ming-ep02/keyframes/pei-door.png', OUT/'keyframes/pei-door.png')
    prior = json.loads((OLD/'manifest.json').read_text(encoding='utf-8'))
    reuse_ids = ['01-lightning-v1', '02-borrow-paper-v1', '05-covered-seal-v1',
                 '06-offer-brush-v1', '09-retrieve-jade-v1', '11-doorway-v1']
    reused = []
    for take in prior['takes']:
        if take['id'] in reuse_ids:
            shutil.copytree(OLD/'clips'/take['id'], OUT/'clips'/take['id'])
            reused.append({**take, 'reuseFrom': f'output/jie-ming-ep01/clips/{take["id"]}'})
    common = ('One continuous live-action Chinese period-drama take at normal human speed. '
              'Preserve the exact reference face, age, hairstyle, clothing and daylight wooden study. '
              'Stay on the same conversation axis, no internal cuts or camera orbit. '
              'Only the visible speaker speaks, clear connected Mandarin with natural lip movement. '
              'Start the response promptly; brief natural punctuation, no dramatic pause or drawn-out syllables. '
              'After finishing close the mouth and listen. No added words, narrator, score or subtitles. ')
    woman = ('The woman in crimson looks at her partner just offscreen RIGHT. '
             'Natural young adult female speaking voice. No laugh, gasp, exaggerated blinking or changing facial emotion. ')
    man = ('The man in teal looks at his partner just offscreen LEFT. '
           'Natural adult male conversation, not a bass announcer or villain growl. ')
    specifications = [
        ('03-request-v2','pei-medium.png',260,'裴衡','师妹，命牌借我用三天。名字签这儿，到时候我给你送回来。',
         man+'He asks a familiar favor as if she usually agrees; persuasion stays casual, not threatening. Keep hands below crop. '),
        ('04-purpose-v1','shen-tight.png',175,'沈照','你渡劫，怎么还用得上我的命牌？',
         woman+'She wants a practical explanation before lending; genuine pointed question, not a rhetorical recital. Stable seated posture. '),
        ('05-reassure-v1','pei-medium.png',192,'裴衡','帮我压一压阵。三天就还，你又不吃亏。',
         man+'He gives an easy dismissive reassurance to get her to sign, stress 三天 without a sinister smile. '),
        ('07-turn-paper-v2','shen-tight.png',158,'沈照','把纸翻过来，我看看背面。',
         woman+'She asks to inspect the document offscreen below, practical matter-of-fact, no theatrical challenge. '),
        ('08-stall-v1','pei-medium.png',209,'裴衡','背面都是套话。借三天，正面不都写着吗？',
         man+'He tries to dismiss the request and draw attention to the front of the paper. A slightly impatient question, conversational phrasing. Hands stay still below crop. '),
        ('09-move-hand-v1','shen-tight.png',124,'沈照','那你把手挪一下。',
         woman+'A simple direct request to move his hand away from the document. She keeps looking at him, no nod or smile. '),
        ('12-name-seal-v1','shen-tight.png',175,'沈照','这是换命印吧？你怎么不告诉我？',
         woman+'She has recognized a concealed term and asks him to account for it. Hurt restrained beneath a direct question, not a shout or smug victory. '),
        ('13-source-v2','pei-medium.png',175,'裴衡','你从哪儿听来的？谁让你看这个的？',
         man+'He stops reassuring her and wants the source of her information. Mild sudden urgency in the question, minimal head motion, no large grimace. '),
        ('15-refuse-v2','shen-standing.png',175,'沈照','不借了。这张纸，我拿去问师父。',
         woman+'She is already standing, keeps the ONE folded paper in her RIGHT hand throughout and refuses the loan. She will ask the master, not plead for approval. Do not switch the paper hand. '),
        ('17-door-demand-v1','pei-door.png',141,'裴衡','你去问可以。纸留下。',
         man+'He is standing in the doorway, blocking the exit without touching her. An ordinary firm demand to recover the paper, no growl. '),
    ]
    fresh = []
    for index,(key,image,frames,speaker,line,direction) in enumerate(specifications):
        fresh.append({'id':key,'image':image,'frames':frames,'seed':2026091950+index,
                      'mode':'dialogue','prompt':common+direction+'Say exactly once: '+line,
                      'lines':[{'speaker':speaker,'text':line}]})
    fresh.extend([
        {'id':'10-open-paper-v1','image':'paper-ready.png','frames':158,'seed':2026091961,'mode':'narration',
         'prompt':'One continuous real-time live-action close insert, same worn wooden tabletop, single folded ivory contract, dark red circular seal, jade and brush. The teal-sleeved male hand on the RIGHT immediately withdraws away from the paper. The crimson-sleeved female RIGHT hand entering from LEFT lifts the loose upper flap and opens this SINGLE folded sheet flat toward screen left, revealing the red seal inside at lower right. Complete this simple paper-opening action within three seconds. Her hand then rests beside the near edge. Keep the same paper, mark and tabletop; no new sheets, writing, fingers, or objects. Fixed camera, no zoom, cut or slow motion. Paper rustle only, no human voice, score, captions or narration.', 'lines':[]},
        {'id':'11-understands-v1','image':'shen-tight.png','frames':141,'seed':2026091962,'mode':'narration',
         'prompt':'One continuous live-action reaction in this exact daylight study. Same seated woman, crimson robe, silver hairpins. She starts looking down at the document just below frame, then raises ONLY her gaze to the man offscreen RIGHT once, within two seconds. She considers what she has found, restrained focused expression, no smile, tears or open mouth. The head barely shifts, not an exaggerated frozen mannequin. Hands stay below frame. Locked close-up, ordinary speed, no cut or camera movement. Quiet room ambience only, absolutely no speech, music, narrator, captions, sighs or gasps.', 'lines':[]}
    ])
    manifest = {'title':'借命不还','episode':1,'episodeTitle':'背面','revision':'opening-pair-v2',
                'source':'original','format':'novel-narration-with-native-dialogue','stage':'in_production',
                'approvedScript':'screenplays/jie-ming-bu-huan/revisions/opening-pair-v2/episode-01.txt',
                'narrator':'Shen Zhao first person; Qwen3-TTS Serena single master',
                'narrationMaster':'audio/narration-master.wav',
                'editOrder':['N1','N2','03-request-v2','04-purpose-v1','05-reassure-v1','N3',
                             '07-turn-paper-v2','08-stall-v1','09-move-hand-v1','N4',
                             '12-name-seal-v1','13-source-v2','N5','15-refuse-v2','B1','17-door-demand-v1'],
                'nativeAmbienceTakes':reuse_ids+['10-open-paper-v1','11-understands-v1'],
                'narrationBlocks':[
                    {'id':'N1','segments':[0,1,2,3],'takes':['01-lightning-v1','02-borrow-paper-v1']},
                    {'id':'N2','segments':[4,5],'takes':['02-borrow-paper-v1']},
                    {'id':'N3','segments':[6,7,8,9],'takes':['06-offer-brush-v1','05-covered-seal-v1']},
                    {'id':'N4','segments':[10,11,12,13,14,15],'takes':['10-open-paper-v1','11-understands-v1']},
                    {'id':'N5','segments':[16,17,18],'takes':['09-retrieve-jade-v1']}],
                'ambientBlocks':[{'id':'B1','take':'11-doorway-v1','inFrame':0,'outFrame':100}],
                'takes':reused+fresh,'artisticApproval':False,'publicationApproval':False}
    save(OUT/'manifest.json',manifest)
    save(OUT/'edit-overrides.json',{'narrationPictures':{}})
    print('Prepared',OUT,'with',len(fresh),'new takes and',len(reused),'reused sources')


if __name__=='__main__':
    main()
