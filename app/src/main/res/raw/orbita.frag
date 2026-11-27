precision highp float;
uniform vec2 size;
uniform vec3 rim, depth;
uniform float wake, zoom, phase, after;
#ifndef ORBITA_RADIAL
#define ORBITA_RADIAL 0
#endif
#ifndef ORBITA_GAS
#define ORBITA_GAS 0
#endif

float square(float x) { return x*x; }

float innerWave(vec2 uv, float layerOffset) {
    if (after <= 0.0) return 0.0;
    float wave = sin(7.2*uv.x + 4.1*uv.y - 2.3*phase + layerOffset);
    return after * wave * (1.0 - smoothstep(.73, 1.0, length(uv)));
}

vec3 layer(vec3 below, vec2 pos, float index, float scale) {
    vec2 center = vec2(180.0+200.0*wake+60.0*zoom,156.0+257.0*wake-48.0*zoom);
    center += index*vec2(2.0+9.0*wake,1.0+5.0*wake);
    float radius = 41.0+292.0*wake+294.0*zoom;
    float amp = (3.4+15.0*wake)*(.6+.4*sin(3.14159*zoom));
    vec2 delta = pos-center;
    float distance = length(delta);
    float aa = .8/scale;
    if (distance > radius+1.58*amp+aa) return below;
    float coverage = 1.0;
    if (distance >= radius-1.58*amp-aa) {
        float angle = atan(delta.y,delta.x);
        float p = (4.1+.31*index)*phase+index*2.35;
        float wave = sin(7.0*angle+p)+.40*sin(11.0*angle-1.24*p)+.18*sin(16.0*angle+1.53*p);
        coverage = smoothstep(-aa,aa,radius+amp*wave-distance);
    }
    vec2 uv = delta/(radius+amp*.40);
    float d = min(1.0,length(uv));
    vec3 light = mix(rim,depth,index*.035);
    vec3 dark = mix(depth,rim,index*.035);
    vec3 color = mix(mix(light,dark,.09),mix(dark,light.brg,.16),clamp((uv.y+.64)/1.28,0.0,1.0));
    if (index > .5) color = color*.51+dark*.43;
    color = mix(color*(.52+.48*pow(d,1.25)),mix(light,vec3(1.0),.52),.44*pow(d,5.0));
    color += light * (.025 * innerWave(uv, index*1.75));
    if (ORBITA_GAS != 0) {
        float flow = phase*(.60+.07*index)+1.75*index;
        vec2 drift = uv+.17*vec2(sin(3.8*uv.y+flow),cos(3.1*uv.x-flow*.86));
        float veil = .5+.5*sin(6.8*drift.x-5.1*drift.y+1.85*sin(4.0*drift.y+flow*.8)+flow);
        float filament = .5+.5*cos(9.2*drift.y+2.4*sin(3.7*drift.x-flow*.75)+3.6*drift.x);
        float fog = pow(veil,3.3)*(.31+.69*filament);
        float upper = exp(-square((uv.y+.58+.24*uv.x)/.19)-square((uv.x+.35)/.60));
        float lower = exp(-square((uv.y-.61+.25*uv.x)/.22)-square((uv.x-.38)/.53));
        float interior = 1.0-smoothstep(.76,.99,d);
        color += light*(.19*fog+.16*upper+.10*lower)*interior;
    }
    float opacity = mix(.57,.33,index);
    return mix(below,color,coverage*(opacity+.21*pow(d,1.65)));
}

vec3 singleBubble(vec3 background, vec2 pos, float scale) {
    vec2 delta = pos-vec2(180.+200.*wake+60.*zoom,156.+257.*wake-48.*zoom);
    float radius = 41.+292.*wake+294.*zoom;
    float dist = length(delta);
    if (dist > radius+7.85+.75/scale) return background;
    float angle = atan(delta.y,delta.x+0.00001);
    float patchA = smoothstep(.83,.99,cos(angle-2.3-.26*sin(phase*.8)));
    float patchB = smoothstep(.89,.995,cos(angle+.72+.20*sin(phase*.6)));
    float wave = (1.2+4.1*wake)*(patchA*sin(9.*angle-3.15*phase)+.48*patchB*sin(13.*angle+2.35*phase));
    float inward = (radius+wave-dist)/radius;
    float coverage = smoothstep(-.75/scale,.75/scale,inward*radius);
    float d = max(0.,inward);
    vec2 xy = delta/radius;
    float side = .63+.37*smoothstep(-.86,.9,dot(xy,vec2(-.9,-.44)));
    float halo = exp(-d*10.5);
    float white = exp(-d*58.);
    float softLight = zoom*(.032+.048*(1.-smoothstep(-.8,.8,xy.y)));
    vec3 fill = rim*(.042+softLight+halo*.61*side+.028*innerWave(xy,0.0));
    if (ORBITA_GAS != 0) {
        // Two slow flows curl the translucent wisps inside the bubble.
        float flow = phase*.38;
        vec2 drift = xy + .17*vec2(sin(3.8*xy.y+flow),cos(3.1*xy.x-flow*.86));
        float veil = .5+.5*sin(6.8*drift.x-5.1*drift.y+1.85*sin(4.0*drift.y+flow*.8)+flow);
        float filament = .5+.5*cos(9.2*drift.y+2.4*sin(3.7*drift.x-flow*.75)+3.6*drift.x);
        float fog = pow(veil,3.3)*(.31+.69*filament);
        float sheen = .55+.45*smoothstep(0.,.18,dist/radius)*cos(4.0*angle+5.0*d+.31*sin(flow));
        float upper = exp(-square((xy.y+.58+.24*xy.x)/.19)) *
                      exp(-square((xy.x+.35)/.60));
        float lower = exp(-square((xy.y-.61+.25*xy.x)/.22)) *
                      exp(-square((xy.x-.38)/.53));
        float detail = mix(1.,.17,smoothstep(0.,.85,zoom));
        fill += detail*fog*rim*(.08+.14*sheen);
        fill += detail*(.23*upper+.13*lower)*rim*(.70+.55*sheen);
        fill += detail*exp(-square((d-.034)/.032))*rim*(.06+.12*sheen)*side;
        fill += rim*detail*.16*fog;
    }
    vec3 brightRim = mix(rim,vec3(1.),.90);
    vec3 color = mix(fill,brightRim,clamp(white*(.76+.24*side)+halo*.16*side,0.,1.));
    return mix(background,color,coverage);
}

void main() {
    float scale = max(size.x/360.0,size.y/780.0);
    vec2 pixel = vec2(gl_FragCoord.x,size.y-gl_FragCoord.y);
    vec2 pos = (pixel-size*.5)/scale+vec2(180.0,390.0);
    vec3 background = vec3(2.0,2.0,7.0)/255.0;
    vec3 color;
    if (ORBITA_RADIAL != 0) color = singleBubble(background,pos,scale);
    else color = layer(layer(background,pos,0.0,scale),pos,1.0,scale);
    float dither = fract(dot(floor(pixel),vec2(.75487766,.56984029)))-.5;
    gl_FragColor = vec4(color+dither/255.0,1.0);
}
