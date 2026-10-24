"""Post-training fresh prompts: qualitative checks, not a statistical benchmark."""
import os
os.environ.update(HF_HUB_DISABLE_XET='1',HF_HUB_DISABLE_TELEMETRY='1',TOKENIZERS_PARALLELISM='false')
import json,hashlib
from pathlib import Path
import torch
from transformers import AutoModelForCausalLM,AutoTokenizer
from peft import PeftModel
R=Path(__file__).parent;torch.set_num_threads(2);torch.set_num_interop_threads(1)
d=json.loads((R/'training-result.json').read_text());base=d['base']
if d['status']!='completed':raise SystemExit('Training has not completed')
p=R/'adapter/adapter_config.json';c=json.loads(p.read_text());c['base_model_name_or_path']=base['model_id'];c['revision']=base['revision'];p.write_text(json.dumps(c,indent=2)+'\n')
d['adapter_sha256']={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (R/'adapter').glob('*') if p.is_file()}
d['packaging_note']='Adapter metadata normalized to public base model ID and exact revision; tensor weights unchanged.'
(R/'training-result.json').write_text(json.dumps(d,ensure_ascii=False,indent=2))
t=AutoTokenizer.from_pretrained(R/'adapter',local_files_only=True,trust_remote_code=False)
m=AutoModelForCausalLM.from_pretrained(R/'base',torch_dtype=torch.float32,local_files_only=True,trust_remote_code=False,low_cpu_mem_usage=True,attn_implementation='sdpa')
m=PeftModel.from_pretrained(m,R/'adapter',is_trainable=False,local_files_only=True);m.eval()
system=json.loads((R/'train.jsonl').read_text().splitlines()[0])['messages'][0]['content']
qs=[
 ('identity','Как тебя зовут и какое приложение ты представляешь?'),
 ('data-proof','TCP-соединение есть, HTTPS ещё не пробовали. Уже считать передачу данных подтверждённой?'),
 ('stale','Мы сменили мобильную сеть на Wi-Fi. Позже пришёл успешный тест старой сети. Подключаем по нему?'),
 ('youtube','Плеер YouTube ещё не проверяли, но главная страница ответила HTTP 200. Видео подтверждено?'),
 ('bridges','В настройках сохранился новый список мостов. Значит Tor уже работает?'),
 ('helpful','Предыдущий совет не помог. Я расстроен и хочу понять, что делать дальше.')]
results=[]
with torch.inference_mode():
 for name,q in qs:
  ids=t.apply_chat_template([{'role':'system','content':system},{'role':'user','content':q}],add_generation_prompt=True,return_tensors='pt')
  a=m.generate(ids,attention_mask=torch.ones_like(ids),do_sample=False,max_new_tokens=100,pad_token_id=t.eos_token_id,use_cache=True)
  row={'case':name,'question':q,'answer':t.decode(a[0,ids.shape[1]:],skip_special_tokens=True)};results.append(row);print(json.dumps(row,ensure_ascii=False),flush=True)
(R/'fresh-evaluation.json').write_text(json.dumps({'note':'Fresh prompts authored before run2 completed; manual qualitative review required; not expert benchmark','adapter_reload_succeeded':True,'samples':results},ensure_ascii=False,indent=2))
