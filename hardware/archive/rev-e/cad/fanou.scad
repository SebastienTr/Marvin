// =====================================================================
//  FANOU - the little lighthouse (Marvin rev E)
//  A desk companion: the lidar is the lamp turning at the top, the
//  screen is its face, the radars sleep behind the painted bands.
//
//  Parametric OpenSCAD source. Units: mm.
//  Frame: X = right, Y = front (-) to back (+), Z = up. Tower axis = Z.
//
//  PART = "base" | "bottom" | "shell" | "spine" | "cap" | "template"   (assembly frame)
//         "print_base" | "print_bottom" | "print_shell" | "print_spine" | "print_cap"
//         "assembly" | "exploded" | "section"
//
//  SPDX-License-Identifier: CERN-OHL-P-2.0
// =====================================================================

PART = "assembly";
$fn = 96;
eps = 0.01;

// ---------------- Tower -------------------------------------------------
zb  = 22;        // tower bottom = top face of the base
zt  = 170;       // tower top = underside of the gallery
Rb  = 52;        // outer radius at the bottom
Rt  = 40;        // outer radius at the top
ws  = 1.6;       // shell wall: half a wavelength at 60 GHz in PETG, so it acts as a radome
k   = (Rb - Rt) / (zt - zb);
a   = atan(k);   // taper angle: every front module leans back by it and looks slightly up
function r(z) = Rb - k * (z - zb);

// Face plane: the front of every module, parallel to the wall and "gap" behind it
gap = 0.8;
y0  = -(Rb - ws - gap);
module F() { translate([0, y0, zb]) rotate([-a, 0, 0]) children(); }
// F frame: x = right, y = depth (backwards), z = height measured along the face
function Fy(w, u) = y0 + w * cos(a) + u * sin(a);
function Fz(w, u) = zb - w * sin(a) + u * cos(a);
// A flat module in a round tower must sit back so its corners clear the wall.
// sb(h, u) = depth of the module front for a half-width h whose top is at height u.
function Rin(u) = r(Fz(0, u)) - ws - 0.3;
function sb(h, u) = ceil((Rin(u) + 0.3 - gap - sqrt(Rin(u)*Rin(u) - h*h)) * 10) / 10;

// ---------------- Fits / fasteners ---------------------------------------
clr     = 0.4;
colFoam = 3.0;                     // 3 mm double-sided foam pads hold the modules
m3_clear = 3.4; m3_pilot = 2.6; m25_pilot = 2.1;
tripod_nut_af = 11.5; tripod_nut_h = 6.0;

// ---------------- Modules (face-frame heights) -----------------------------
kitW = 35; kitH = 54; kitD = 22; kitU = 6;       // MR60BHA2 in its Seeed case, portrait
ldW = 44;  ldH = 15;  ldT = 1.6; ldU = 62;       // HLK-LD2450 behind the red band
lcdW = 31.5; lcdH = 39; lcdT = 4.5; lcdStand = 4.0; lcdU = 81;  // Waveshare 1.69", portrait like a window
lcdWinW = 28.8; lcdWinH = 33.4;                  // opening for the 27.97 x 32.63 active area
lcdSx = 13.25;                                   // standoff columns (pads are wide)
xW = 21; xH = 17.8; xD = 15; xU = 122;           // XIAO ESP32S3 Sense, lens forward
camD = 12;
g = 1.6 + clr/2;                                  // guide thickness beyond the module edge
kitS = sb(kitW/2 + g, kitU + kitH);
ldS  = sb(ldW/2 + g, ldU + ldH);
lcdS = sb(lcdW/2 + g, lcdU + lcdH);
xS   = sb(xW/2 + g, xU + xH);
echo(setbacks = [kitS, ldS, lcdS, xS]);

// Spine: tilted plate behind all modules
spD = kitS + kitD + 2.8;
spT = 3; spW = 50; spTopU = 139;

// ---------------- Lidar (D800 / D500, LD19 footprint) ---------------------
lidPairDY = 8.3; lidSingleDY = -23.6; slot = 1.2; lidY = 0;

// ---------------- Cap (gallery + lantern plinth) --------------------------
galR = Rt + 7; galT = 4; capDn = 6;              // floor from zt-capDn to zt+galT
plinthH = 6;
railR = galR - 3; railH = 13; nPosts = 18;

// ---------------- Base (rocky island) ------------------------------------
baseR = 66; baseRt = 60; baseZ0 = 5; cavR = 46;
spkL = 30.4; spkH = 20.4; spkT = 5;

// =====================================================================
module rrect(w, d, r, h) {
    hull() for (x=[-w/2+r, w/2-r], y=[-d/2+r, d/2-r]) translate([x,y,0]) cylinder(r=r, h=h, $fn=32);
}
module slotY(d, len, h) { hull() for (s=[-len, len]) translate([0,s,0]) cylinder(d=d, h=h, $fn=24); }
module lidarHoles() {
    for (x=[-23.4, 23.4]) translate([x, lidY - lidPairDY, 0]) children();
    translate([0, lidY - lidSingleDY, 0]) children();
}
rockJ = [1.00,0.94,1.02,0.96,1.04,0.95,0.99,1.03,0.94,1.01,0.97,1.05,0.95];
rockPts = [for (i=[0:12]) [cos(i*360/13)*rockJ[i], sin(i*360/13)*rockJ[i]]];
module rock(h, r0, r1) { linear_extrude(height=h, scale=r1/r0) scale([r0, r0]) polygon(rockPts); }

// thin layer hugging the outside of the tower, used to make reliefs
module skin(t) {
    difference() {
        translate([0,0,zb]) cylinder(r1=Rb+t, r2=Rt+t, h=zt-zb);
        translate([0,0,zb-1]) cylinder(r1=Rb-0.3, r2=Rt-0.3, h=zt-zb+2);
    }
}
// shapes projected forward through the wall (in the F frame, facing -y)
module throughF(u) { F() translate([0, 30, u]) rotate([90,0,0]) linear_extrude(60) children(); }
module arch(w, h) { translate([-w/2, -h]) square([w, h - w/2]); translate([0, -w/2]) circle(d=w); }

// =====================================================================
// SHELL - the striped tower (print upright; colour changes by layer)
module shell() {
    difference() {
        translate([0,0,zb]) cylinder(r1=Rb, r2=Rt, h=zt-zb);
        translate([0,0,zb-eps]) cylinder(r1=Rb-ws, r2=Rt-ws, h=zt-zb+2*eps);
        throughF(lcdU + lcdH/2) offset(r=3) offset(delta=-3) square([lcdWinW, lcdWinH], center=true);   // face
        throughF(xU + xH/2) circle(d=camD);                                                              // camera porthole
        for (ang=[150, 180, 210]) rotate([0,0,ang-90]) translate([r(146), 0, 146]) cube([10, 5, 16], center=true);  // back windows = vents
        for (ang=[0, 120, 240]) rotate([0,0,ang-90]) translate([r(zt-3), 0, zt-3]) rotate([0,90,0]) cylinder(d=m3_clear, h=10, center=true, $fn=24);  // cap screws
        translate([-2.5, Rb-ws-1.5, zb-eps]) cube([5, 4, 4]);                                           // key notch
    }
    // face plaque: flat inside for the screen, chamfered underneath so it prints without supports
    difference() {
        intersection() {
            F() hull() {
                translate([-(lcdWinW/2 + 7), -(gap + ws + 1.0), lcdU - 4]) cube([lcdWinW + 14, 0.01, lcdH + 5]);
                translate([-(lcdWinW/2 + 7), lcdS - 0.4, lcdU - 4 - (lcdS + gap + ws + 1)]) cube([lcdWinW + 14, 0.01, lcdH + 5 + (lcdS + gap + ws + 1)]);
            }
            translate([0,0,zb]) cylinder(r1=Rb+20, r2=Rt+20, h=zt-zb);
        }
        translate([0,0,zb-0.01]) cylinder(r1=Rb-ws+0.01, r2=Rt-ws+0.01, h=zt-zb+0.02);   // outside only: nothing inside the tower
        throughF(lcdU + lcdH/2) offset(r=3) offset(delta=-3) square([lcdWinW, lcdWinH], center=true);
        F() translate([0, -(gap + ws + 1.0) - 0.01, lcdU + lcdH/2]) rotate([-90,0,0]) linear_extrude(2.2, scale=[lcdWinW/(lcdWinW+4.4), lcdWinH/(lcdWinH+4.4)]) offset(r=3) offset(delta=-3) square([lcdWinW + 4.4, lcdWinH + 4.4], center=true);
    }
    // reliefs: arched door around the 60 GHz radar, face frame, brass porthole ring
    intersection() { skin(0.8); throughF(kitU + kitH + 3) difference() { arch(kitW + 9, kitH + 7); translate([0, -2.4]) arch(kitW + 3, kitH + 2); } }
    intersection() { skin(1.0); throughF(xU + xH/2) difference() { circle(d=camD + 7); circle(d=camD); } }
}

// =====================================================================
// SPINE - carries every front module; screwed onto the base, slides up into the shell
module cradle(W, H, U, S, depth) {   // rails start at the module front S and run back "depth" (to the plate)
    for (s=[-1,1]) translate([s>0 ? W/2+clr/2 : -W/2-clr/2-1.6, S, U-1.6]) cube([1.6, depth, H+clr+3.2]);
    for (s=[-1,1], top=[0,1]) translate([s>0 ? W/2+clr/2-4 : -W/2-clr/2-1.6, S, top ? U+H+clr : U-1.6]) cube([5.6, depth, 1.6]);
}
module spine() {
    difference() {
        union() {
            F() {
                translate([-spW/2, spD, -6]) cube([spW, spT, spTopU + 6]);
                // MR60 case (portrait): guides reach the plate, foam on the plate
                cradle(kitW, kitH, kitU, kitS, spD - kitS + eps);
                // LD2450: shallow guides + two foam pillars at the board ends
                cradle(ldW, ldH, ldU, ldS, spD - ldS + eps);
                for (s=[-1,1]) translate([s*19 - 2.5, ldS + ldT + colFoam, ldU + ldH/2 - 3]) cube([5, spD - ldS - ldT - colFoam + eps, 6]);
                // LCD: guides + pillars on the brass standoffs
                cradle(lcdW, lcdH, lcdU, lcdS, spD - lcdS + eps);
                for (s=[-1,1]) translate([s*lcdSx - 3, lcdS + lcdT + lcdStand + colFoam, lcdU + 2]) cube([6, spD - (lcdS + lcdT + lcdStand + colFoam) + eps, lcdH - 4]);
                // XIAO: guides + central pillar
                cradle(xW, xH, xU, xS, spD - xS + eps);
                translate([-4, xS + xD + colFoam, xU + xH/2 - 4]) cube([8, spD - (xS + xD + colFoam) + eps, 8]);
            }
            // foot flange: forward under the MR60 case, screwed from inside the base
            translate([-spW/2, Fy(0,0) - 0.5, zb]) cube([spW, Fy(spD + spT, 0) - Fy(0,0) + 0.5, 3]);
        }
        translate([-100, -100, zb - 50]) cube([200, 200, 50]);           // flat bottom on the base
        difference() { translate([0,0,zb-1]) cylinder(r=200, h=zt); translate([0,0,zb-1.001]) cylinder(r1=Rb-ws-clr+k, r2=Rt-ws-clr-k*1, h=zt-zb+2); }
        F() for (u=[34, 72, 100]) translate([0, spD - 1, u]) rotate([-90,0,0]) rrect(14, 8, 3, spT + 2);  // wire holes
        for (x=[-12, 12]) translate([x, Fy(10, 0), zb - 1]) cylinder(d=m3_pilot, h=10, $fn=24);
        F() translate([-6, kitS - 1, kitU - 2]) cube([12, kitD + 2, 2.4]);   // room for the MR60 USB-C plug
        // XIAO: the Dupont plugs on its two pin rows go through the plate (slots above and below the pillar)
        F() for (u=[xU - 3, xU + xH/2 + 4.5]) translate([-10, spD - 1, u]) cube([20, spT + 2, (u < xU ? xU + xH/2 - 4 - u : spTopU + 1 - u)]);
        // XIAO: notch both side guides for a 90° USB-C plug (whichever side the port ends up)
        F() for (s=[-1,1]) translate([s*(xW/2 + clr/2 + 0.8) - 2, xS - 1, xU + xH/2 - 5.5]) cube([4, spD - xS + 2, 11]);
    }
}

// =====================================================================
// CAP - gallery floor, railing and lantern plinth (print upright, floor on the bed)
module cap() {
    difference() {
        union() {
            translate([0,0,zt-capDn]) cylinder(r=galR, h=capDn+galT);
            for (i=[0:nPosts-1]) rotate([0,0,i*360/nPosts]) translate([railR,0,zt+galT]) cylinder(d=2.4, h=railH, $fn=16);
            translate([0,0,zt+galT+railH-2]) difference() { cylinder(r=railR+1.4, h=2.2); translate([0,0,-1]) cylinder(r=railR-1.4, h=5); }
            translate([0, lidY, zt+galT]) rrect(60, 54, 6, plinthH);
        }
        // groove taking the shell rim
        translate([0,0,zt-capDn-1]) difference() { cylinder(r=Rt+clr+0.8, h=capDn+1); translate([0,0,-1]) cylinder(r=Rt-ws-clr, h=capDn+3); }
        // lighten the underside inside the rim
        translate([0,0,zt-capDn-1]) cylinder(r=Rt-ws-clr-8, h=capDn-1);
        // lidar: pilot holes for M2.5 thread-forming screws (no nuts)
        lidarHoles() translate([0,0,zt+galT+plinthH-8]) slotY(m25_pilot, slot, 9);
        // lidar cable
        translate([12, lidY + 24, zt-capDn-1]) rrect(12, 8, 3, capDn+galT+plinthH+2);
        // radial cap screws (through the shell rim)
        for (ang=[0, 120, 240]) rotate([0,0,ang-90]) translate([0, 0, zt-3]) rotate([0,90,0]) {
            translate([0,0,Rt-ws-clr-8]) cylinder(d=m3_pilot, h=9, $fn=24);
            translate([0,0,Rt-1]) cylinder(d=m3_clear, h=10, $fn=24);
            translate([0,0,galR-1.6]) cylinder(d=6.2, h=3, $fn=24);
        }
    }
}

// =====================================================================
// BASE - the rocky island (print upside down, top face on the bed)
module base() {
    difference() {
        translate([0,0,baseZ0]) rock(zb-baseZ0, baseR, baseRt);
        translate([0,0,baseZ0-eps]) cylinder(r=cavR, h=zb-baseZ0-3);
        translate([0,0,zb-3]) difference() { cylinder(r=Rb+clr, h=4); translate([0,0,-1]) cylinder(r=Rb-ws-clr, h=6); }
        translate([0, 12, zb-5]) rrect(60, 40, 8, 10);                          // into the tower
        for (x=[-12, 12]) translate([x, Fy(10, 0), zb-6]) {                    // spine foot screws
            cylinder(d=m3_clear, h=10, $fn=24);
        }
        translate([0, cavR-4, baseZ0+6]) rotate([-90,0,0]) hull() for (s=[-2.8,2.8]) translate([s,0,0]) cylinder(d=9, h=30, $fn=24);  // USB-C cable
        for (i=[0:15]) rotate([0,0,i*360/16 + 11]) translate([cavR+8, 0, baseZ0+5]) cube([20, 2.4, 5], center=true);  // sound slots
    }
    translate([-2, Rb-ws-clr, zb-3.01]) cube([4, 2.8, 3.02]);                  // key
    difference() {
        for (ang=[45,135,225,315]) rotate([0,0,ang]) translate([cavR-3, 0, baseZ0]) cylinder(d=9, h=zb-baseZ0-3+eps, $fn=32);
        for (ang=[45,135,225,315]) rotate([0,0,ang]) translate([cavR-3, 0, baseZ0-1]) cylinder(d=m3_pilot, h=10, $fn=24);
    }
}

// BOTTOM - plate with speaker grille and a 1/4"-20 tripod nut (stick silicone feet underneath)
module bottom() {
    zp = baseZ0 - 2.4;
    difference() {
        union() {
            translate([0,0,zp]) cylinder(r=cavR+5, h=2.4);
            translate([-(spkL+3.2)/2, -(spkH+3.2)/2 - 18, zp+2.4]) difference() { cube([spkL+3.2, spkH+3.2, 3]); translate([1.6,1.6,-1]) cube([spkL, spkH, 5]); }
            translate([0, 20, zp+2.4]) cylinder(d=18, h=7);
        }
        for (x=[-12:4:12], y=[-6:4:6]) translate([x, y-18, zp-4]) cylinder(d=2.4, h=10, $fn=16);
        for (ang=[45,135,225,315]) rotate([0,0,ang]) translate([cavR-3, 0, zp-4]) { cylinder(d=m3_clear, h=10, $fn=24); cylinder(d=6.2, h=4+1.4, $fn=24); }
        translate([0, 20, zp-5]) cylinder(d=6.8, h=20, $fn=32);
        translate([0, 20, zp+2.4+7-tripod_nut_h]) rotate([0,0,30]) cylinder(d=tripod_nut_af/cos(30), h=tripod_nut_h+1, $fn=6);
    }
}

module template() {
    difference() {
        translate([0, lidY, 0]) rrect(62, 58, 4, 2);
        translate([0,0,-1]) lidarHoles() slotY(2.9, slot, 4);
        translate([0, lidY, -1]) cylinder(d=4, h=4);
    }
}

// =====================================================================
module ghosts() {
    color("DimGray") F() translate([-kitW/2, kitS, kitU+clr/2]) cube([kitW, kitD, kitH]);
    color("SeaGreen") F() translate([-ldW/2, ldS, ldU+clr/2]) cube([ldW, ldT, ldH]);
    color("White") F() translate([-lcdW/2, lcdS, lcdU+clr/2]) cube([lcdW, lcdT, lcdH]);
    color("Gold") F() for (s=[-1,1], u=[lcdU+3, lcdU+lcdH-3]) translate([s*lcdSx, lcdS+lcdT, u]) rotate([-90,0,0]) cylinder(d=4, h=lcdStand, $fn=12);
    color("RoyalBlue") F() translate([-xW/2, xS, xU+clr/2]) cube([xW, xD, xH]);
    color("Goldenrod") translate([-spkL/2, -spkH/2-18, baseZ0]) cube([spkL, spkH, spkT]);
    color("Black") translate([0, lidY, zt+galT+plinthH]) {
        translate([-38.59/2, -38.59/2, 0]) cube([38.59, 38.59, 22.2]);
        translate([0,0,22.2]) cylinder(d=35.29, h=12.6);
    }
}

// Print orientations (no supports)
module print_base()   translate([0,0,zb]) rotate([180,0,0]) base();
module print_bottom() translate([0,0,-(baseZ0-2.4)]) bottom();
module print_shell()  translate([0,0,-zb]) shell();
module print_cap()    translate([0,0,-(zt-capDn)]) cap();
module print_spine()  translate([0,0,spD+spT]) rotate([-90,0,0]) rotate([a,0,0]) translate([0,-y0,-zb]) spine();

module assemblyView(e=0) {
    color("SlateGray") base();
    color("DimGray") translate([0,0,-e*0.6]) bottom();
    color("IndianRed") translate([0,0,e*1.2]) shell();
    color("Tan") spine();
    color("Navy") translate([0,0,e*2.0]) cap();
    ghosts();
}

if (PART == "base") base();
else if (PART == "bottom") bottom();
else if (PART == "shell") shell();
else if (PART == "spine") spine();
else if (PART == "cap") cap();
else if (PART == "template") template();
else if (PART == "print_base") print_base();
else if (PART == "print_bottom") print_bottom();
else if (PART == "print_shell") print_shell();
else if (PART == "print_spine") print_spine();
else if (PART == "print_cap") print_cap();
else if (PART == "print_template") template();
else if (PART == "exploded") assemblyView(40);
else if (PART == "section") difference() { assemblyView(0); translate([0,-150,-10]) cube([300,300,400]); }
else assemblyView(0);
