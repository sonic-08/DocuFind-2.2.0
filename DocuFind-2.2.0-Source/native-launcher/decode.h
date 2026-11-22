/* Decode one LZ4 block. Return success only when the whole block is consumed. */
static int expand_block(const unsigned char *s,unsigned n,unsigned char *d,unsigned cap) {
    unsigned i=0,j=0;
    while(i<n) {
        unsigned token=s[i++], literals=token>>4, match=token&15, x;
        if(literals==15) do { if(i==n) return 0; x=s[i++]; if(literals>cap || x>cap-literals)return 0; literals+=x; } while(x==255);
        if(literals>n-i || literals>cap-j)return 0;
        while(literals--)d[j++]=s[i++];
        if(i==n)return j==cap;
        if(n-i<2)return 0;
        unsigned offset=s[i]|((unsigned)s[i+1]<<8); i+=2;
        if(!offset || offset>j)return 0;
        if(match==15) do { if(i==n)return 0; x=s[i++]; if(match>cap || x>cap-match)return 0; match+=x; } while(x==255);
        if(cap-j<4 || match>cap-j-4)return 0;
        match+=4;
        while(match--){d[j]=d[j-offset];j++;}
    }
    return j==cap;
}
