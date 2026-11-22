#include "bundle.h"
#include "decode.h"
typedef unsigned short W;
typedef unsigned int U;
typedef void *H;
typedef unsigned long long Size;
extern U GetEnvironmentVariableW(const W*,W*,U);
extern U GetFileAttributesW(const W*);
extern int CreateDirectoryW(const W*,void*);
extern H CreateFileW(const W*,U,U,void*,U,U,H);
extern int WriteFile(H,const void*,U,U*,void*);
extern int FlushFileBuffers(H);
extern int CloseHandle(H);
extern H GetProcessHeap(void);
extern void *HeapAlloc(H,U,Size);
extern int HeapFree(H,U,void*);
extern H CreateMutexW(void*,int,const W*);
extern U WaitForSingleObject(H,U);
extern int ReleaseMutex(H);
extern H ShellExecuteW(H,const W*,const W*,const W*,const W*,int);
extern int MessageBoxW(H,const W*,const W*,U);
extern void ExitProcess(U);
extern const unsigned char payload[],payload_end[];
static W base[32768],file[32768];
static unsigned length(const W*s){unsigned i=0;while(s[i])i++;return i;}
static int join(W*out,const W*dir,const W*name){
    U a=length(dir),b=length(name); if(a+b+2>=32768)return 0;
    for(U i=0;i<a;i++)out[i]=dir[i]; out[a++]='\\';
    for(U i=0;i<=b;i++)out[a+i]=name[i]; return 1;
}
static U number(const unsigned char*p){return p[0]|((U)p[1]<<8)|((U)p[2]<<16)|((U)p[3]<<24);}
static int directory(const W*path){return CreateDirectoryW(path,0) || ((GetFileAttributesW(path)&0x10)!=0 && GetFileAttributesW(path)!=0xffffffff);}
static int writefile(const W*path,const unsigned char*p,U n){
    H h=CreateFileW(path,0x40000000,0,0,2,0x80,0); if(h==(H)-1)return 0;
    U done=0; int ok=WriteFile(h,p,n,&done,0) && done==n;
    if(ok && !n)ok=FlushFileBuffers(h); return CloseHandle(h)&&ok;
}
static int unpack(void){
    unsigned prefix=length(base); const unsigned char*p=payload;
    while(payload_end-p>=12){
        U name=number(p),size=number(p+4),stored=number(p+8);p+=12;
        if(!name)return size==0 && !stored && p==payload_end;
        if(name%2 || prefix+name/2+2>=32768 || (Size)(payload_end-p)<(Size)name+stored)return 0;
        for(U i=0;i<prefix;i++)file[i]=base[i];file[prefix]='\\';
        for(U i=0;i<name/2;i++)file[prefix+1+i]=p[i*2]|((U)p[i*2+1]<<8);
        file[prefix+1+name/2]=0;p+=name;
        if(size==0xffffffff){if(stored || !directory(file))return 0;}
        else if(stored==size){if(!writefile(file,p,size))return 0;}
        else {
            unsigned char *bytes=HeapAlloc(GetProcessHeap(),0,size);
            if(!bytes)return 0;
            int ok=expand_block(p,stored,bytes,size) && writefile(file,bytes,size);
            HeapFree(GetProcessHeap(),0,bytes);if(!ok)return 0;
        }
        p+=stored;
    }
    return 0;
}
static void fail(const W*msg){MessageBoxW(0,msg,L"DocuFind Explorer",0x10);ExitProcess(1);}
void entry(void){
    U n=GetEnvironmentVariableW(L"LOCALAPPDATA",file,32768);
    if(!n || n>=32768 || !join(base,file,RELEASE) || !directory(base))fail(L"Cannot create the local DocuFind folder.");
    H lock=CreateMutexW(0,0,L"Local\\" RELEASE); if(!lock)fail(L"Cannot prepare DocuFind.");
    U acquired=WaitForSingleObject(lock,120000);
    if(acquired!=0 && acquired!=128)fail(L"Another DocuFind launch is still preparing the application.");
    if(!join(file,base,L"bundle.ready"))fail(L"The installation path is too long.");
    if(GetFileAttributesW(file)==0xffffffff){
        if(!unpack() || !join(file,base,L"bundle.ready") || !writefile(file,(const unsigned char*)"",0))
            fail(L"Could not extract DocuFind. Check free disk space and folder permissions.");
    }
    ReleaseMutex(lock);CloseHandle(lock);
    if(!join(file,base,L"DocuFind.exe") || (Size)ShellExecuteW(0,L"open",file,0,base,1)<=32)
        fail(L"Could not open the extracted DocuFind application.");
    ExitProcess(0);
}
