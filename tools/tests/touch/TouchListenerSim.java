import java.util.*;
/** Simulazione fedele di CanvasOnTouchListener.kt (vecchio e nuovo) con eventi sintetici.
 *  Modello del nativo = NativeInput::OnTouchEvent: down/up/move impostano 'down' (move => down=true). */
public class TouchListenerSim {
  static final int DOWN=0, UP=1, MOVE=2, CANCEL=3, PDOWN=5, PUP=6;
  static class Ev { int act, idx; int[] id, x, y;
    Ev(int act,int idx,int[] id,int[] x,int[] y){this.act=act;this.idx=idx;this.id=id;this.x=x;this.y=y;}
    int findPointerIndex(int pid){ for(int i=0;i<id.length;i++) if(id[i]==pid) return i; return -1; } }
  static class Native { boolean down; int x,y; List<String> log=new ArrayList<>();
    void touch(String n,int x,int y,boolean st){ this.x=x;this.y=y;this.down=st; log.add(n+"("+x+","+y+")"); } }
  interface L { boolean onTouch(Ev e, Native n); }
  // ---- VECCHIO (traduzione riga per riga) ----
  static class Old implements L { int cur=-1;
    public boolean onTouch(Ev e, Native n){
      int pi=e.idx; int pid=e.id[pi];
      if(cur!=-1 && pid!=cur) return false;
      int x=e.x[pi], y=e.y[pi];
      switch(e.act){
        case DOWN: case PDOWN: n.touch("down",x,y,true); return true;
        case UP: case PUP: cur=-1; n.touch("up",x,y,false); return true;
        case MOVE: n.touch("move",x,y,true); return true; }
      return false; } }
  // ---- NUOVO ----
  static class New implements L { int cur=-1; int lx,ly;
    public boolean onTouch(Ev e, Native n){
      switch(e.act){
        case DOWN: cur=e.id[e.idx]; lx=e.x[e.idx]; ly=e.y[e.idx]; n.touch("down",lx,ly,true); return true;
        case PDOWN: if(cur!=-1) return false; cur=e.id[e.idx]; lx=e.x[e.idx]; ly=e.y[e.idx]; n.touch("down",lx,ly,true); return true;
        case MOVE: { int i=e.findPointerIndex(cur); if(i==-1) return false; lx=e.x[i]; ly=e.y[i]; n.touch("move",lx,ly,true); return true; }
        case UP: case PUP: { if(e.id[e.idx]!=cur) return false; cur=-1; n.touch("up",e.x[e.idx],e.y[e.idx],false); return true; }
        case CANCEL: { if(cur==-1) return false; cur=-1; n.touch("up",lx,ly,false); return true; } }
      return false; } }

  // ===== Overlay (Button / Joystick): replica della logica di stato, VECCHIO senza ACTION_CANCEL, NUOVO con ACTION_CANCEL =====
  static class Joy { boolean fixed; int cur=-1; boolean pressed; double ax, ay; double cx=100, cy=100, r=50;
    Joy(boolean withCancel){ this.fixed=withCancel; }
    void onTouch(Ev e){
      switch(e.act){
        case DOWN: case PDOWN: { int i=e.idx; if(Math.hypot(e.x[i]-100,e.y[i]-100)<=r){ cx=e.x[i]; cy=e.y[i]; cur=e.id[i]; pressed=true; ax=ay=0; } break; }
        case UP: case PUP: if(cur==e.id[e.idx]){ cx=100; cy=100; cur=-1; pressed=false; ax=ay=0; } break;
        case CANCEL: if(fixed && cur!=-1){ cx=100; cy=100; cur=-1; pressed=false; ax=ay=0; } break;
        case MOVE: if(cur!=-1) for(int i=0;i<e.id.length;i++) if(e.id[i]==cur){ double x=(e.x[i]-cx)/r, y=(e.y[i]-cy)/r, n=Math.hypot(x,y); if(n>1){x/=n;y/=n;} pressed=true; ax=x; ay=y; } break; } } }
  static void overlay(){
    System.out.println();
    System.out.printf("%-46s | %-30s | %-30s%n","overlay: joystick spinto e poi CANCEL","VECCHIO","NUOVO");
    List<Ev> seq=List.of(ev(DOWN,0,a(0),a(100),a(100)),ev(MOVE,0,a(0),a(160),a(100)),ev(CANCEL,0,a(0),a(160),a(100)));
    Joy o=new Joy(false), n=new Joy(true); for(Ev e: seq){ o.onTouch(e); n.onTouch(e); }
    System.out.printf("%-46s | %-30s | %-30s%n","stato finale (pressed, asse X)", (o.pressed?"BLOCCATO x="+String.format("%.1f",o.ax):"ok"), (n.pressed?"BLOCCATO":"ok x="+String.format("%.1f",n.ax)));
  }
  static Ev ev(int act,int idx,int[] id,int[] x,int[] y){ return new Ev(act,idx,id,x,y); }
  static int[] a(int... v){return v;}
  public static void main(String[] s){
    Map<String,List<Ev>> sc=new LinkedHashMap<>();
    sc.put("S1 un dito: tap + trascinamento", List.of(ev(DOWN,0,a(0),a(10),a(10)),ev(MOVE,0,a(0),a(20),a(20)),ev(UP,0,a(0),a(20),a(20))));
    sc.put("S2 due dita: il 2o si alza, il 1o continua", List.of(ev(DOWN,0,a(0),a(10),a(10)),ev(PDOWN,1,a(0,1),a(10,500),a(10,300)),ev(PUP,1,a(0,1),a(10,500),a(10,300)),ev(MOVE,0,a(0),a(30),a(30)),ev(UP,0,a(0),a(30),a(30))));
    sc.put("S3 CANCEL (gesto di sistema / tendina)", List.of(ev(DOWN,0,a(0),a(100),a(100)),ev(MOVE,0,a(0),a(120),a(110)),ev(CANCEL,0,a(0),a(120),a(110))));
    sc.put("S4 il 1o dito si alza, il 2o resta", List.of(ev(DOWN,0,a(0),a(10),a(10)),ev(PDOWN,1,a(0,1),a(10,500),a(10,300)),ev(PUP,0,a(0,1),a(10,500),a(10,300)),ev(MOVE,0,a(1),a(520),a(310)),ev(UP,0,a(1),a(520),a(310))));
    sc.put("S5 DOWN senza UP/CANCEL poi nuovo DOWN (vista staccata)", List.of(ev(DOWN,0,a(0),a(10),a(10)),ev(DOWN,0,a(7),a(50),a(60)),ev(UP,0,a(7),a(50),a(60))));
    sc.put("S6 CANCEL con 2 dita", List.of(ev(DOWN,0,a(0),a(10),a(10)),ev(PDOWN,1,a(0,1),a(10,500),a(10,300)),ev(CANCEL,0,a(0,1),a(10,500),a(10,300))));
    System.out.printf("%-52s | %-34s | %-34s%n","scenario","VECCHIO: fine / eventi","NUOVO: fine / eventi");
    for(var en: sc.entrySet()){
      Native no=new Native(), nn=new Native(); L o=new Old(), n=new New();
      for(Ev e: en.getValue()){ o.onTouch(e,no); n.onTouch(e,nn); }
      System.out.printf("%-52s | %-6s %-26s | %-6s %-26s%n",en.getKey(), no.down?"BLOCCATO":"ok", String.join(">",no.log).replace("down","D").replace("move","m").replace("up","U").replaceAll("\\(\\d+,\\d+\\)",""), nn.down?"BLOCCATO":"ok", String.join(">",nn.log).replace("down","D").replace("move","m").replace("up","U").replaceAll("\\(\\d+,\\d+\\)",""));
    }
    overlay();
  }
}
