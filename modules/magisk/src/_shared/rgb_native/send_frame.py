"""Send per-pixel RGB test patterns using only Python's standard library."""
import argparse,socket,struct,time,uuid

def ddp(sequence,offset,data,push=True):
    return struct.pack('>BBBBIH',0x40|int(push),sequence,0x0b,1,offset,len(data))+bytes(data)

def sacn(sequence,data,universe=1,channel=1,cid=bytes(range(16))):
    data=bytes(channel-1)+bytes(data);n=126+len(data);p=bytearray(n)
    struct.pack_into('>HH',p,0,16,0);p[4:16]=b'ASC-E1.17\0\0\0'
    struct.pack_into('>HI',p,16,0x7000+n-16,4);p[22:38]=cid
    struct.pack_into('>HI',p,38,0x7000+n-38,2);p[44:54]=b'RGB-test01'
    p[108]=100;p[111]=sequence;struct.pack_into('>H',p,113,universe)
    struct.pack_into('>HBBHHH',p,115,0x7000+n-115,2,0xa1,0,1,len(data)+1);p[126:]=data
    return p

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('ip');parser.add_argument('--count',type=int,required=True)
    parser.add_argument('--protocol',choices=['ddp','sacn'],default='ddp')
    parser.add_argument('--universe',type=int,default=1);parser.add_argument('--channel',type=int,default=1)
    parser.add_argument('--colors',default='FF0000,00FF00,0000FF')
    parser.add_argument('--seconds',type=float,default=30);parser.add_argument('--multicast',action='store_true')
    args=parser.parse_args()
    if args.count not in (1,24) or not 1<=args.universe<=63999 or args.channel<1 or args.channel+args.count*3-1>512:parser.error('Use 1 stock pixel or 24 custom pixels; check universe and channel')
    try:
        colors=[bytes.fromhex(v.strip().lstrip('#')) for v in args.colors.split(',')]
        if any(len(c)!=3 for c in colors):raise ValueError()
    except ValueError:parser.error('Colors must be comma-separated RRGGBB values')
    data=b''.join(colors[i%len(colors)] for i in range(args.count));cid=uuid.uuid4().bytes
    with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as sock:
        if args.multicast:
            with socket.socket(socket.AF_INET,socket.SOCK_DGRAM) as route:
                route.connect((args.ip,5568));local=route.getsockname()[0]
            sock.setsockopt(socket.IPPROTO_IP,socket.IP_MULTICAST_IF,socket.inet_aton(local));sock.setsockopt(socket.IPPROTO_IP,socket.IP_MULTICAST_TTL,16)
        destination=('239.255.%d.%d'%(args.universe>>8,args.universe&255) if args.multicast else args.ip,5568 if args.protocol=='sacn' else 4048)
        print('Sending',args.protocol,args.count,'RGB pixels to',destination,flush=True)
        stop=time.monotonic()+args.seconds;sequence=1
        try:
            while time.monotonic()<stop:
                packet=ddp(sequence%15+1,0,data) if args.protocol=='ddp' else sacn(sequence%256,data,args.universe,args.channel,cid)
                sock.sendto(packet,destination);sequence+=1;time.sleep(.05)
        except KeyboardInterrupt:pass
