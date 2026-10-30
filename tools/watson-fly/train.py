"""Frozen real-connectome encoder + domain-split historical phishing readout; no URL visits."""
import os
os.environ['OPENBLAS_NUM_THREADS']='2'
from pathlib import Path
import numpy as np,pandas as pd,json,hashlib,sys
from scipy.sparse import csr_matrix
R=Path(os.environ.get('WATSON_REPO',str(Path(__file__).resolve().parents[2])));O=Path(os.environ.get('WATSON_ANALYSIS','/data/analysis'));sys.path.insert(0,str(Path(__file__).parent))
from feature_baseline import sigmoid,metrics
raw=Path('/data/raw/phiusiil.csv');old=json.load(open(O/'watson-evidence.json'))
assert hashlib.sha256(raw.read_bytes()).hexdigest()==old['csv_sha256']
a=np.load(O/'watson-derived-features.npz');X,y,split=a['X'],a['y'],a['split']
assert len(X)==old['retained_rows'] and set(np.unique(y))=={0,1}
# Independent label cross-foot from immutable CSV after exact-URL deduplication.
df=pd.read_csv(raw,usecols=['URL','label']).drop_duplicates('URL')
assert len(df)==len(X) and int((df.label==0).sum())==int(y.sum())
tr,va,te=(split==n for n in ['train','validation','test'])
mean=X[tr].mean(0);scale=X[tr].std(0);scale[scale<1e-8]=1
U=np.clip((X-mean)/scale,-6,6)
# Engineered adapter: five fixed groups, not biological sensory neurons.
groups=[[0,1,2,3,13],[4,5,19,20],[6,7,8,9,10,11,12],[14,15,16,17,18],[21,22]]
F=np.column_stack([(np.tanh(U[:,g].mean(1))+1.0)/2.0 for g in groups])
g=json.load(open('/data/roki-fly/graph.json'));edges=np.array(g['edges']);n=len(g['nodes_zero_based_matlab_rows']);pre,post,w=edges.T
inc=np.bincount(post,weights=w,minlength=n);norm=.6*w/np.maximum(inc[post],1)
M=csr_matrix((norm,(pre,post)),shape=(n,n));projection=np.array([[(((i*17+j*31)%17)-8)/8 for j in range(5)]for i in range(n)])
pools=np.column_stack([(np.arange(n)%16==i)/(np.arange(n)%16==i).sum() for i in range(16)])
def reservoir(A):
 drive=A@projection.T;state=np.tanh(drive)
 for _ in range(3):state=np.tanh(drive+(M.T@state.T).T)
 return state@pools
P=np.empty((len(X),16))
for start in range(0,len(X),2048):P[start:start+2048]=reservoir(F[start:start+2048])
Z=np.column_stack([np.ones(len(X)),U,P]);A=Z[tr];b=y[tr];coef=np.zeros(Z.shape[1]);m=coef.copy();v=coef.copy()
for epoch in range(1,301):
 grad=A.T@(sigmoid(A@coef)-b)/len(b);grad[1:]+=.001*coef[1:]
 m=.9*m+.1*grad;v=.999*v+.001*grad*grad
 coef-=.03*(m/(1-.9**epoch))/(np.sqrt(v/(1-.999**epoch))+1e-8)
scores=sigmoid(Z@coef);benign=np.sort(scores[va & (y==0)]);threshold=float(np.nextafter(benign[int(np.ceil(.99*len(benign)))-1],np.inf))
fixtures=[{'raw':X[i].tolist(),'input':F[i].tolist(),'pool':P[i].tolist(),'score':float(scores[i])} for i in [0,100,1000]]
model={'mean':mean.tolist(),'scale':scale.tolist(),'weights':coef.tolist(),'threshold':threshold,'groups':groups,'fixtures':fixtures,'graph_sha256':hashlib.sha256(Path('/data/roki-fly/graph.json').read_bytes()).hexdigest()}
(R/'tools/watson-fly').mkdir(parents=True,exist_ok=True);(R/'tools/watson-fly/model.json').write_text(json.dumps(model,indent=2))
code='''package com.vlesscardvpn.watson
import com.vlesscardvpn.core.FlyReservoir
import kotlin.math.*
/** Frozen FlyWire-derived encoder + historical PhiUSIIL readout. Not malware detection. */
object WatsonFlyUrl {
'''
for name in ['mean','scale','weights']:code+='    private val '+name+'=doubleArrayOf('+','.join(repr(v) for v in model[name])+')\n'
code+='    const val threshold='+repr(threshold)+'\n'
code+='    private val groups=arrayOf('+','.join('intArrayOf('+','.join(map(str,g))+')'for g in groups)+')\n'
code+='''    fun score(x:DoubleArray):Double? {
        if(x.size!=23 || x.any{!it.isFinite()}) return null
        val u=DoubleArray(23){((x[it]-mean[it])/scale[it]).coerceIn(-6.0,6.0)}
        val input=DoubleArray(5){g->(tanh(groups[g].sumOf{u[it]}/groups[g].size)+1.0)/2.0}
        val encoded=FlyReservoir.encode(input)
        var z=weights[0]
        for(i in u.indices) z+=weights[1+i]*u[i]
        for(i in 0 until 16) z+=weights[24+i]*encoded[6+i]
        return 1.0/(1.0+exp(-z.coerceIn(-35.0,35.0)))
    }
}
'''
(R/'app/src/main/java/com/vlesscardvpn/watson/WatsonFlyUrl.kt').write_text(code)
e={'source':old['source'],'attribution':old['attribution'],'raw_sha256':old['csv_sha256'],'feature_source_sha256':hashlib.sha256((O/'watson-derived-features.npz').read_bytes()).hexdigest(),'graph_sha256':model['graph_sha256'],'train_rows':int(tr.sum()),'validation_rows':int(va.sum()),'test_rows':int(te.sum()),'split':old['split_method'],'validation':metrics(y[va],scores[va],threshold),'test':metrics(y[te],scores[te],threshold),'threshold':threshold,'training_epochs':300,'features':40,'network_requests_to_samples':0,'comparison_plan':{'delivery':'prose','reason':'Engineering model artifact, no current-malware, device or battery comparison; no effectiveness chart justified.'},'limitations':old['limitations']+['Artificial input projection/readout on restricted graph, not whole-brain emulation.','Independent model, not proof of improvement over previous model.','Deployed as additional advisory only; no automatic blocking.']}
(O/'watson-fly-evidence.json').write_text(json.dumps(e,indent=2));print('Historical model trained; rows',e['train_rows'],e['validation_rows'],e['test_rows'],'features',e['features']);print('Not antivirus, not live-threat validation.')
