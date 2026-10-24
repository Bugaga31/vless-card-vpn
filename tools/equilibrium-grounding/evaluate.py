"""Run the pinned local LLM on explicit local sources. No network, actions or training.

Only this CLI imports llama_cpp; contract unit tests have no third-party dependency.
Raw responses stay local and may contain unpublished source quotations.
"""
import argparse
import hashlib
import importlib.metadata
import json
from pathlib import Path
import time
from grounding import Source, retrieve, make_prompt, validate, Rejected

MODEL_SHA256='3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597'
MODEL_ID='unsloth/Qwen3-4B-Instruct-2507-GGUF'
MODEL_REVISION='a06e946bb6b655725eafa393f4a9745d460374c9'

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--model',type=Path,required=True)
    parser.add_argument('--cases',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    a=parser.parse_args()
    with a.model.open('rb') as model_file:
        digest=hashlib.file_digest(model_file,'sha256').hexdigest()
    if digest!=MODEL_SHA256:raise SystemExit('Model checksum mismatch')
    from llama_cpp import Llama
    model=Llama(model_path=str(a.model),n_ctx=4096,n_threads=2,n_threads_batch=2,
                n_batch=128,n_ubatch=64,n_gpu_layers=0,use_mmap=True,verbose=False,
                chat_format='chatml',seed=11001)
    cases=json.loads(a.cases.read_text())
    results=[]
    for case in cases:
        source=Source(case['source_id'],case['title'],Path(case['source_path']).read_text())
        evidence=retrieve(source,case['question'],case.get('aliases',[]))
        messages=make_prompt(case['question'],evidence)
        # Conservative context bound; never silently truncate source text mid-quote.
        while evidence and len(model.tokenize(json.dumps(messages,ensure_ascii=False).encode()))>3000:
            evidence.pop(); messages=make_prompt(case['question'],evidence)
        started=time.monotonic()
        result={'id':case['id'],'question':case['question'],'source_sha256':source.digest,
                'evidence':evidence,'manual_semantic_review':'pending'}
        if not evidence:
            result.update(raw=None,gate={'status':'insufficient','actions_executed':False},rejected=False)
        else:
            response=model.create_chat_completion(messages=messages,temperature=0,max_tokens=512,seed=11001)
            choice=response['choices'][0];raw=choice['message']['content']
            result.update(raw=raw,finish_reason=choice['finish_reason'],usage=response.get('usage'))
            try:
                result.update(gate=validate(raw,evidence,source,choice['finish_reason']),rejected=False)
            except Rejected as exc:
                result.update(gate=None,rejected=True,rejection=str(exc))
        result['seconds']=round(time.monotonic()-started,2)
        results.append(result)
        manifest={'model_id':MODEL_ID,'model_revision':MODEL_REVISION,'model_sha256':digest,
                  'llama_cpp_python':importlib.metadata.version('llama-cpp-python'),
                  'weight_training_performed':False,'android_integration':False,
                  'semantic_success_claimed':False,'code_sha256':{n:hashlib.sha256((Path(__file__).parent/n).read_bytes()).hexdigest() for n in ['grounding.py','evaluate.py']},'results':results}
        a.output.write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps({'id':case['id'],'rejected':result['rejected'],'seconds':result['seconds']},ensure_ascii=False),flush=True)

if __name__=='__main__':main()
