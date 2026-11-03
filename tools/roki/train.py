from pathlib import Path
import json,hashlib,gzip,base64
import numpy as np
import pandas as pd
from scipy.io import loadmat
import argparse
p=argparse.ArgumentParser();p.add_argument('--raw',type=Path,required=True);p.add_argument('--out',type=Path,required=True);args=p.parse_args()
RAW=args.raw;OUT=args.out;OUT.mkdir(parents=True,exist_ok=True)
import urllib.request
record=json.load(urllib.request.urlopen('https://zenodo.org/api/records/18555170'))
checks={}
for f in record['files']:
 b=(RAW/f['key']).read_bytes();assert 'md5:'+hashlib.md5(b).hexdigest()==f['checksum'];checks[f['key']]={'sha256':hashlib.sha256(b).hexdigest(),'md5':f['checksum']}
a=loadmat(RAW/'annotations.mat',simplify_cells=True);labels=np.asarray(a['labels']['class']);names=list(a['names']['class']);W=loadmat(RAW/'connectome.mat',simplify_cells=True)['W']['TOT'].tocsr()
assert W.shape==(len(labels),len(labels));assert labels.min()==1 and labels.max()==len(names);assert np.isfinite(W.data).all() and np.all(W.data>0) and np.equal(W.data,np.floor(W.data)).all()
rng=np.random.default_rng(216)
selected=[];counts={}
for name,n in [('Kenyon_Cell',384),('DAN',32),('MBON',96),('MBIN',4)]:
 ix=np.flatnonzero(labels==names.index(name)+1);assert len(ix)>=n
 part=np.sort(rng.choice(ix,n,replace=False));selected.extend(part.tolist());counts[name]=n
nodes=np.array(sorted(selected));sub=W[nodes][:,nodes].tocoo();order=np.lexsort((sub.col,sub.row));pre=sub.row[order].astype(int);post=sub.col[order].astype(int);weights=sub.data[order].astype(int)
assert len(set(nodes))==len(nodes);assert len(pre)>0
# Independent raw slice checksum/cross-foot, not reuse of COO accumulation.
assert int(weights.sum())==int(W[nodes][:,nodes].sum());assert len(pre)==W[nodes][:,nodes].count_nonzero()
edges=pd.DataFrame({'pre':pre,'post':post,'synapses':weights});edges.to_csv(OUT/'retained_connections.csv',index=False)
graph={'source':'https://zenodo.org/records/18555170','license':'CC-BY-4.0','matrix':'W.TOT; A_ij synapses from i to j, paper S4','nodes_zero_based_matlab_rows':nodes.tolist(),'edges':edges.values.tolist(),'selection_seed':216,'selection_counts':counts}
graph_bytes=json.dumps(graph,separators=(',',':')).encode();(OUT/'graph.json').write_bytes(graph_bytes)
n=len(nodes);incoming=np.bincount(post,weights=weights,minlength=n);normalized=0.6*weights/np.maximum(incoming[post],1)
projection=np.array([[(((i*17+j*31)%17)-8)/8 for j in range(5)]for i in range(n)])
def encode(X):
 drive=X@projection.T;state=np.tanh(drive)
 for _ in range(3):
  propagated=np.zeros_like(state)
  for k in range(len(X)): propagated[k]=np.bincount(post,weights=state[k,pre]*normalized,minlength=n)
  state=np.tanh(drive+propagated)
 pooled=np.array([state[:,np.arange(n)%16==g].mean(axis=1) for g in range(16)]).T
 return np.column_stack([np.ones(len(X)),X,pooled])
# These are generated software scenarios, NOT phone/provider observations or proof of utility.
rng=np.random.default_rng(1216);X=rng.uniform(0,1,(2048,5));X[:,0]=1;X[:,3]=(X[:,3]>.5).astype(float)
latent=1.2-2*X[:,1]-1.5*X[:,2]+1.3*X[:,3]+1.2*X[:,4]
y=(rng.uniform(size=len(X))<1/(1+np.exp(-latent))).astype(float)
Z=encode(X);coef=np.zeros(Z.shape[1]);train=np.arange(1536);test=np.arange(1536,2048)
for _ in range(300):
 pred=1/(1+np.exp(-np.clip(Z[train]@coef,-20,20)));coef+=.2*(Z[train].T@(y[train]-pred)/len(train)-.001*coef)
loss=lambda ids:float(np.mean(np.logaddexp(0,Z[ids]@coef)-y[ids]*(Z[ids]@coef)))
fixtures=[]
for x in [np.array([1.,.01,.289,1.,.8]),np.array([1.,.8,.9,0.,.1]),np.array([1.,0.,0.,0.,0.])]: fixtures.append({'input':x.tolist(),'encoded':encode(x[None,:])[0].tolist()})
model={'graph_sha256':hashlib.sha256(graph_bytes).hexdigest(),'weights':coef.tolist(),'training':'synthetic software scenarios only; not validated on device/network','seed':1216,'train_rows':len(train),'heldout_rows':len(test),'train_logloss':loss(train),'heldout_logloss':loss(test),'fixtures':fixtures}
(OUT/'model.json').write_text(json.dumps(model,indent=2))
evidence={'source':graph['source'],'raw_checks':checks,'raw_nodes':len(labels),'raw_directed_edges':int(W.nnz),'raw_synapses':int(W.sum()),'retained_nodes':n,'retained_edges':len(pre),'retained_synapses':int(weights.sum()),'classes':counts,'graph_sha256':model['graph_sha256'],'orientation_source':'https://journals.plos.org/complexsystems/article?id=10.1371/journal.pcsy.0000091 (S4)','model':model,'comparison_plan':{'question':'Can connectome-derived encoder and readout be reproduced across Python and Kotlin?','grain':'retained directed connection + synthetic scenario','population':'restricted MB circuit, not whole brain','unit':'software regression','finding':'Real graph extracted and synthetic readout trained; utility on VPN unmeasured','delivery':'prose','reason':'Engineering task, no real network comparison or trend to visualize.'},'limits':['516-neuron subset is not an entire fly brain','Tanh dynamics, normalization, feature projection and output pooling are engineering choices','All edges excitatory in this simplified reservoir; no claim of biological sign/physiology','Synthetic data do not prove network ranking accuracy','No phone performance, battery, memory or security effectiveness measured']}
(OUT/'evidence.json').write_text(json.dumps(evidence,indent=2))
print(json.dumps({k:evidence[k]for k in ['retained_nodes','retained_edges','retained_synapses','graph_sha256']}));print('Synthetic training completed; cross-language fixtures exported. No real network accuracy claim.')
