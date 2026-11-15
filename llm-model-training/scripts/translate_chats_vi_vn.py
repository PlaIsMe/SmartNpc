"""Create a local Vietnamese draft from English, preserving machine facts and split membership.

Use the separate CPU translation environment; never run during model retraining.
Existing Vietnamese source/evaluation files are never overwritten. Review the draft.
"""
import argparse
import copy
import hashlib
import json
import re
import tomllib
from pathlib import Path
from model_paths import MODEL_ROOT, TRAINING_ROOT

TRANSLATOR = 'Helsinki-NLP/opus-mt-en-vi'
REVISION = '989c9fb9ec63987901022baf0182dcec3e149be6'
GLOSSARY = {
 'health bar':'thanh máu','health points':'máu','health':'máu','hunger bar':'thanh đói','hunger meter':'chỉ số đói',
 'stone pickaxe':'cuốc đá','iron pickaxe':'cuốc sắt','wooden pickaxe':'cuốc gỗ','pickaxes':'cuốc','pickaxe':'cuốc',
 'diamond sword':'kiếm kim cương','iron sword':'kiếm sắt','enchanted sword':'kiếm phù phép','armor':'giáp',
 'raw iron':'sắt thô','raw gold':'vàng thô','raw copper':'đồng thô','iron ore':'quặng sắt','gold ore':'quặng vàng',
 'coal ore':'quặng than','diamond ore':'quặng kim cương','ancient debris':'mảnh vỡ cổ đại',
 'netherite block':'khối netherite','netherite blocks':'khối netherite','diamond block':'khối kim cương',
 'diamond blocks':'khối kim cương','emerald block':'khối ngọc lục bảo','emerald blocks':'khối ngọc lục bảo',
 'netherite':'netherite','cobblestone':'đá cuội','diamonds':'kim cương','diamond':'kim cương',
 'coal':'than','iron ingot':'phôi sắt','copper ingot':'phôi đồng','gold ingot':'phôi vàng',
 'golden apple':'táo vàng','enchanted golden apple':'táo vàng phù phép','apples':'táo','apple':'táo',
 'bread':'bánh mì','loaf':'bánh mì','loaves':'bánh mì','bow':'cung','bows':'cung','arrows':'mũi tên',
 'sticks':'gậy','stick':'gậy','logs':'gỗ','log':'gỗ','wheat':'lúa mì','beacon':'đèn hiệu','beacons':'đèn hiệu',
 'carrot':'cà rốt','potato':'khoai tây','cooked beef':'thịt bò chín','respawn':'hồi sinh','inventory':'túi đồ',
 'crouches':'nhún','crouching':'nhún','crouch':'nhún','NPC':'NPC','NPCs':'NPC','PvP':'PvP',
 'lol':'haha','bro':'ông','pleaseee':'với mà','fuck you':'đmm','fuck off':'đcm biến đi',
 'fucking':'đcm','fuck':'đcm','shit':'vãi','bastard':'đồ khốn',
}
for wood,vi in {'oak':'sồi','birch':'bạch dương','spruce':'vân sam','dark oak':'sồi sẫm','jungle':'rừng',
                'acacia':'xiêm gai','mangrove':'đước','cherry':'anh đào'}.items():
    for part,word in {'log':'gỗ','logs':'gỗ','planks':'ván','sapling':'cây non','stairs':'cầu thang','slab':'phiến',
                      'door':'cửa','fence':'hàng rào','boat':'thuyền'}.items():
        GLOSSARY[f'{wood} {part}']=f'{word} {vi}'

ANCHORS = [('banana',r'chuối'),('pineapple',r'dứa|thơm'),('lemon',r'chanh'),('watermelon',r'dưa hấu'),
           ('strawberry',r'dâu tây'),('blueberry',r'việt quất'),('cherry',r'anh đào|cherry'),
           ('grape',r'nho'),('peach',r'đào'),('pear',r'lê'),('mango',r'xoài'),('coconut',r'dừa'),
           ('papaya',r'đu đủ'),('avocado',r'bơ'),('lychee',r'vải')]

def toml_value(value):
    if isinstance(value,dict):return '{ '+', '.join(json.dumps(str(k),ensure_ascii=False)+' = '+toml_value(v) for k,v in value.items())+' }'
    if isinstance(value,list):return '['+', '.join(toml_value(v) for v in value)+']'
    return json.dumps(value,ensure_ascii=False)

def casual(text, seed):
    # Keep most pronouns informal; preserve names, IDs, numbers and emoticons.
    text=re.sub(r'\bcác bạn\b','mọi người',text,flags=re.I)
    text=re.sub(r'\bchúng tôi\b','bọn tao',text,flags=re.I)
    for before,after in [('anh bạn','ông'),('tôi','tao'),('bạn','mày')]:
        text=re.sub(r'\b'+before+r'\b',after,text,flags=re.I)
    text=re.sub(r'\bxin chào\b','hello',text,flags=re.I)
    text=re.sub(r'\blàm ơn\b','',text,flags=re.I)
    text=re.sub(r'\bđược rồi\b','rồi',text,flags=re.I)
    if seed%7==0:
        text=re.sub(r'\btao\b','tớ',text,flags=re.I)
        text=re.sub(r'\bmày\b','cậu',text,flags=re.I)
    elif seed%3==0:
        text=re.sub(r'\btao\b','t',text,flags=re.I)
        text=re.sub(r'\bmày\b','m',text,flags=re.I)
    return re.sub(r'\s+',' ',text).strip(' ,')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source',type=Path,default=TRAINING_ROOT/'data/my_chats_en_us.toml')
    parser.add_argument('--output',type=Path,default=TRAINING_ROOT/'data/my_chats_vi_vn.toml')
    parser.add_argument('--batch-size',type=int,default=48)
    parser.add_argument('--threads',type=int,default=4)
    parser.add_argument('--translate-only',action='store_true',help='Populate the ignored translation cache for editorial review; do not write datasets')
    args=parser.parse_args()
    frozen_path=args.output.parent/'test_vi_vn.jsonl'
    if args.output.exists() or frozen_path.exists():raise ValueError('Vietnamese source/evaluation already exists; edit instead of overwriting')
    document=tomllib.loads(args.source.read_text(encoding='utf-8'))
    originals=document['chat']
    names={name for row in originals for name in (row.get('npc_name','Alex'),row.get('speaker_name','Steve'),row.get('event',{}).get('target','none')) if name!='none'}
    protected=re.compile(r'\b(?:'+'|'.join(re.escape(s) for s in sorted(names,key=len,reverse=True))+r')\b|\b(?i:'+
                         '|'.join(re.escape(s) for s in sorted(GLOSSARY,key=len,reverse=True))+r')\b|\b[a-z0-9_.-]+:[a-z0-9_./-]+\b|\b[A-Z][A-Z_]{2,}\b')
    def masked(text):
        replacements=[]
        def replace(match):
            part=match.group()
            if len(replacements)>=len(ANCHORS):raise ValueError('Too many glossary terms in '+text)
            anchor,pattern=ANCHORS[len(replacements)]
            replacements.append((pattern,part if part in names else GLOSSARY.get(part.lower(),part)))
            return anchor
        return protected.sub(replace,text),replacements
    texts=set()
    for row in originals:
        if row.get('kind','dialogue')!='event':texts.add(row['player'])
        texts.update(row['npc'] if isinstance(row['npc'],list) else [row['npc']])
        texts.update(turn.split(': ',1)[1] for turn in row.get('recent_turns',[]))
    def needs(text):return bool(re.search('[A-Za-z]',text)) and not re.fullmatch(r'[Tt]_[Tt]|[xX][dD]',text.strip())
    fragments=sorted({masked(text)[0] for text in texts if needs(text)})
    tools=MODEL_ROOT/'.translation-tools'
    cache_path=tools/'vi_vn_translation_cache.json'
    cache=json.loads(cache_path.read_text(encoding='utf-8')) if cache_path.exists() else {}
    missing=[s for s in fragments if s not in cache]
    if missing:
        import ctranslate2
        from transformers import MarianTokenizer
        tokenizer=MarianTokenizer.from_pretrained(str(tools/'opus-mt-en-vi'),local_files_only=True)
        engine=ctranslate2.Translator(str(tools/'opus-mt-en-vi-int8'),device='cpu',compute_type='int8',inter_threads=1,intra_threads=args.threads)
        for offset in range(0,len(missing),args.batch_size):
            batch=missing[offset:offset+args.batch_size]
            tokens=[tokenizer.convert_ids_to_tokens(tokenizer.encode('>>vie<< '+text)) for text in batch]
            results=engine.translate_batch(tokens,beam_size=3,num_hypotheses=2,max_decoding_length=100)
            for english,result in zip(batch,results):
                cache[english]=list(dict.fromkeys(tokenizer.decode(tokenizer.convert_tokens_to_ids(h),skip_special_tokens=True).strip() for h in result.hypotheses))
            cache_path.write_text(json.dumps(cache,ensure_ascii=False,indent=2),encoding='utf-8')
            print(f'Translated {min(offset+args.batch_size,len(missing))}/{len(missing)} fragments',flush=True)
    if args.translate_only:return
    def translate(text,seed):
        if not needs(text):return text
        key,replacements=masked(text)
        result=cache[key][0]
        for pattern,word in replacements:
            result=re.sub(pattern,lambda _:word,result,flags=re.I)
        return casual(result,seed)
    translated=[]
    issues=[]
    for index,row in enumerate(originals):
        output=copy.deepcopy(row)
        for field in ('frozen_case','language','speaker_language'):output.pop(field,None)
        if output.get('kind','dialogue')!='event':output['player']=translate(row['player'],index)
        output['recent_turns']=[turn.split(': ',1)[0]+': '+translate(turn.split(': ',1)[1],index) for turn in row.get('recent_turns',[])]
        replies=row['npc'] if isinstance(row['npc'],list) else [row['npc']]
        output['npc']=list(dict.fromkeys(translate(s,index+j) for j,s in enumerate(replies)))
        if row['split']!='test' and (len(output['npc'])<10 or max(map(len,output['npc']))>100):issues.append(index)
        output['source']=row.get('source','Original synthetic dialogue authored for SmartNpc')+f'; Vietnamese draft from {TRANSLATOR}@{REVISION}, conversational post-editing'
        translated.append(output)
    # An ignored review draft may contain long/collapsed translations; fix them before publication.
    draft={'format_version':2,'model_language':'vi_vn','chat':translated}
    (tools/'vi_vn_review_draft.json').write_text(json.dumps(draft,ensure_ascii=False,indent=2),encoding='utf-8')
    print(f'Review draft ready: {len(translated)} blocks; {len(issues)} need length/alternative edits.',flush=True)
    print('Review draft is not the final dataset. Run the editorial preparation step before importing.',flush=True)

if __name__=='__main__':main()
