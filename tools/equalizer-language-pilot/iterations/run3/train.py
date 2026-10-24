"""Small, auditable CPU LoRA pilot; no network actions, user secrets or app deployment."""
import os
os.environ.update(HF_HUB_DISABLE_XET='1',HF_HUB_DISABLE_TELEMETRY='1',DO_NOT_TRACK='1',TOKENIZERS_PARALLELISM='false')
import json,time,random,hashlib,math,subprocess
from pathlib import Path
import torch
from transformers import AutoTokenizer,AutoModelForCausalLM
from huggingface_hub import snapshot_download
from peft import LoraConfig,get_peft_model,TaskType,PeftModel
ROOT=Path(__file__).parent
random.seed(149);torch.manual_seed(149);torch.set_num_threads(2);torch.set_num_interop_threads(1)
base=json.loads((ROOT/'base-model.json').read_text())
started=time.time()
report={'status':'initializing','base':base,'seed':149,'purpose':'limited Russian Equalizer dialogue pilot, NOT a new foundation model or production integration','config':{'epochs':2,'learning_rate':0.0002,'gradient_accumulation':2,'max_length':256,'rank':8,'alpha':16,'layers':list(range(24)),'target_modules':['q_proj','v_proj'],'training_time_limit_s':720},'automatic_promotion':False,'training_curve':[]}
def save():
 (ROOT/'training-result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
def event(stage,**kw):
 print(json.dumps({'stage':stage,**kw},ensure_ascii=False),flush=True)
 report['status']=stage;save()
def rows(name):return [json.loads(x) for x in (ROOT/f'{name}.jsonl').read_text().splitlines()]
try:
 event('downloading_pinned_base')
 snapshot=snapshot_download(base['model_id'],revision=base['revision'],token=False,allow_patterns=['*.json','*.safetensors','merges.txt','vocab.json','LICENSE','README.md'],local_dir=ROOT/'base',max_workers=2)
 report['base_file_sha256']={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (ROOT/'base').glob('*') if p.is_file()}
 tokenizer=AutoTokenizer.from_pretrained(snapshot,trust_remote_code=False,local_files_only=True)
 model=AutoModelForCausalLM.from_pretrained(snapshot,torch_dtype=torch.float32,low_cpu_mem_usage=True,trust_remote_code=False,local_files_only=True,attn_implementation='sdpa')
 model.config.use_cache=False
 parent=ROOT.parent/'run2/adapter'
 model=PeftModel.from_pretrained(model,parent,is_trainable=True,local_files_only=True)
 report['parent_adapter_sha256']=__import__('hashlib').sha256((parent/'adapter_model.safetensors').read_bytes()).hexdigest()
 report['parent_optimizer_steps']=90
 report['baseline_note']='Before is the run2 adapter, not the original base model'

 report['base_parameters']=sum(p.numel() for p in model.parameters())
 def encode(row):
  messages=row['messages'];prompt=tokenizer.apply_chat_template(messages[:-1],tokenize=False,add_generation_prompt=True)
  prompt_ids=tokenizer(prompt,add_special_tokens=False)['input_ids']
  response_ids=tokenizer(messages[-1]['content']+'<|im_end|>',add_special_tokens=False)['input_ids']
  ids=(prompt_ids+response_ids)[:256];labels=([-100]*len(prompt_ids)+response_ids)[:256]
  if sum(x!=-100 for x in labels)<8:raise ValueError('Insufficient answer tokens: '+row['id'])
  return {'input_ids':torch.tensor([ids]),'attention_mask':torch.ones(1,len(ids),dtype=torch.long),'labels':torch.tensor([labels])}
 train=[encode(r) for r in rows('train')];hold=[encode(r) for r in rows('holdout')]
 report['training_examples']=len(train);report['holdout_examples']=len(hold)
 report['training_answer_tokens']=sum(int((r['labels']!=-100).sum()) for r in train)
 report['holdout_answer_tokens']=sum(int((r['labels']!=-100).sum()) for r in hold)
 def evaluate():
  model.eval();total=0.;n=0
  with torch.inference_mode():
   for r in hold:
    count=int((r['labels']!=-100).sum());loss=float(model(**r).loss)
    if not math.isfinite(loss):raise ValueError('Non-finite evaluation loss')
    total+=loss*count;n+=count
  return {'answer_token_nll':total/n,'answer_tokens':n,'examples':len(hold)}
 sample_rows=[rows('holdout')[i] for i in [0,1,4,5]]
 def generate():
  model.eval();out=[]
  with torch.inference_mode():
   for row in sample_rows:
    ids=tokenizer.apply_chat_template(row['messages'][:-1],tokenize=True,add_generation_prompt=True,return_tensors='pt')
    seq=model.generate(ids,attention_mask=torch.ones_like(ids),do_sample=False,max_new_tokens=80,pad_token_id=tokenizer.eos_token_id,use_cache=True)
    out.append({'id':row['id'],'question':row['messages'][-2]['content'],'answer':tokenizer.decode(seq[0,ids.shape[1]:],skip_special_tokens=True)})
  return out
 event('evaluating_base')
 report['before']=evaluate();report['baseline_samples']=generate();save()
 # Continue the explicitly loaded parent adapter; do not reset LoRA weights.
 params=[p for p in model.parameters() if p.requires_grad]
 report['trainable_parameters']=sum(p.numel() for p in params)
 before={n:p.detach().clone() for n,p in model.named_parameters() if p.requires_grad}
 optimizer=torch.optim.AdamW(params,lr=2e-4,weight_decay=0.01,foreach=False)
 model.train();optimizer.zero_grad(set_to_none=True)
 train_start=time.time();step=0;micro=0;stopped=False
 event('training',trainable_parameters=report['trainable_parameters'])
 for epoch in range(2):
  order=list(range(len(train)));random.Random(149+epoch).shuffle(order)
  for idx in order:
   batch=train[idx];loss=model(**batch).loss
   if not torch.isfinite(loss):raise ValueError('Non-finite training loss')
   (loss/2).backward();micro+=1
   if micro%2==0:
    norm=float(torch.nn.utils.clip_grad_norm_(params,1.0))
    if not math.isfinite(norm):raise ValueError('Non-finite gradient')
    optimizer.step();optimizer.zero_grad(set_to_none=True);step+=1
    item={'step':step,'epoch':epoch+1,'last_microbatch_loss':float(loss.detach()),'gradient_norm':norm,'elapsed_s':round(time.time()-train_start,2)}
    report['training_curve'].append(item)
    if step%4==0:event('training',**item)
   if time.time()-train_start>720 and micro%2==0:stopped=True;break
  if stopped:break
 report['optimizer_steps']=step;report['microbatches']=micro;report['training_seconds']=round(time.time()-train_start,2);report['time_limit_reached']=stopped
 report['changed_trainable_tensors']=sum(not torch.equal(before[n],p.detach()) for n,p in model.named_parameters() if p.requires_grad)
 report['largest_parameter_change']=max(float((before[n]-p.detach()).abs().max()) for n,p in model.named_parameters() if p.requires_grad)
 if step<1 or report['changed_trainable_tensors']==0:raise ValueError('No actual parameter update')
 adapter=ROOT/'adapter';model.save_pretrained(adapter,safe_serialization=True);tokenizer.save_pretrained(adapter)
 report['adapter_sha256']={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in adapter.glob('*') if p.is_file()}
 event('evaluating_adapter')
 report['after']=evaluate();report['adapted_samples']=generate()
 report['holdout_nll_improved']=report['after']['answer_token_nll']<report['before']['answer_token_nll']
 report['limitations']=['90 original synthetic dialogues; eight inspected validation paraphrases are not a blind benchmark','No full book obtained or used','No claim of consciousness, general reasoning improvement, or network availability','Not integrated into APK; no new release','No credentials, user traffic, private configurations or public endpoint probing','No production promotion even if token loss decreases']
 report['total_seconds']=round(time.time()-started,2)
 (ROOT/'environment.freeze.txt').write_text(subprocess.check_output([str(ROOT/'.venv/bin/pip'),'freeze'],text=True))
 event('completed',optimizer_steps=step,changed_tensors=report['changed_trainable_tensors'],holdout_nll_improved=report['holdout_nll_improved'])
except BaseException as exc:
 report['error_type']=type(exc).__name__;report['error']=str(exc)[:1200];event('failed',error_type=type(exc).__name__);raise
