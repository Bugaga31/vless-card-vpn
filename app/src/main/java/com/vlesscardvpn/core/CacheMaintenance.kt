package com.vlesscardvpn.core

import java.io.File

/** Manual private-cache housekeeping only. Never filesDir, APKs, credentials or another app's data. */
object CacheMaintenance {
    const val MIN_AGE_MS = 24*60*60*1000L
    const val MAX_FILES = 128
    const val MAX_NODES = 2048
    data class Entry(val relative:String,val bytes:Long,val modified:Long)
    data class Preview(val entries:List<Entry>,val limited:Boolean=false) {
        val bytes:Long get()=entries.fold(0L) {a,e->if(Long.MAX_VALUE-a<e.bytes) Long.MAX_VALUE else a+e.bytes}
    }
    data class Result(val deleted:Int,val bytes:Long,val skipped:Int)
    private val extensions=setOf("tmp","part","log","png","jpg","jpeg","webp")
    private fun safe(root:File,f:File,now:Long):Boolean = runCatching {
        val path=f.absoluteFile.normalize()
        val real=f.canonicalFile
        // Comparing the full lexical path also rejects symlinked intermediate directories.
        real==path && real.path.startsWith(root.path+File.separator) && f.isFile &&
            f.extension.lowercase() in extensions && !f.name.lowercase().contains("update") &&
            !f.name.lowercase().contains("backup") && f.lastModified()>0 && now>=f.lastModified() &&
            now-f.lastModified()>=MIN_AGE_MS
    }.getOrDefault(false)
    @Synchronized fun preview(cacheDir:File,now:Long):Preview {
        val root=cacheDir.canonicalFile
        if(!root.isDirectory) return Preview(emptyList())
        val pending=java.util.ArrayDeque<Pair<File,Int>>();pending.add(root to 0)
        val found=mutableListOf<Entry>();var visited=0;var limited=false
        while(pending.isNotEmpty()) {
            val (dir,depth)=pending.removeFirst()
            if(depth>8) {limited=true;continue}
            val children=dir.listFiles()?.sortedBy {it.name}.orEmpty()
            for(f in children) {
                if(++visited>MAX_NODES || found.size>=MAX_FILES) {limited=true;break}
                if(f.canonicalFile!=f.absoluteFile.normalize()) continue
                if(f.isDirectory) pending.add(f to depth+1)
                else if(safe(root,f,now)) found+=Entry(f.relativeTo(root).path,f.length().coerceAtLeast(0),f.lastModified())
            }
            if(visited>MAX_NODES || found.size>=MAX_FILES) {limited=limited || pending.isNotEmpty();break}
        }
        return Preview(found,limited)
    }
    @Synchronized fun clean(cacheDir:File,preview:Preview,now:Long):Result {
        val root=cacheDir.canonicalFile;var deleted=0;var bytes=0L;var skipped=0
        preview.entries.distinctBy {it.relative}.take(MAX_FILES).forEach {e->
            val f=File(root,e.relative)
            if(e.bytes>=0 && safe(root,f,now) && f.length()==e.bytes && f.lastModified()==e.modified && f.delete()) {
                deleted++;bytes=if(Long.MAX_VALUE-bytes<e.bytes) Long.MAX_VALUE else bytes+e.bytes
            } else skipped++
        }
        return Result(deleted,bytes,skipped)
    }
}
