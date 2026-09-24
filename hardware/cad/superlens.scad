// =====================================================================
//  SuperLens — handheld multi-sensor head
//  D500 (STL-19P) 2D lidar + HLK-LD2450 (24 GHz) + Seeed MR60BHA2 (60 GHz) + XIAO ESP32S3 Sense camera + 1.69" ST7789 screen
//
//  Parametric OpenSCAD source. Units: mm.
//  Frame: X = right, Y = front (0) to back, Z = up.
//
//  Export one part:   openscad -D 'PART="print_head"' -o head.stl superlens.scad
//  Or all parts:      ../scripts/export_stl.sh
//  PART = "head" | "cover" | "grip" | "dock" | "template"      (assembly frame)
//         "print_head" | "print_cover" | "print_grip" | "print_dock" | "print_template"
//                                                             (oriented for the print bed)
//         "assembly" | "exploded"                              (preview with module ghosts)
//
//  SPDX-License-Identifier: CERN-OHL-P-2.0
// =====================================================================

PART = "assembly";
$fn = 48;
eps = 0.01;

// ---------- Main enclosure (head) ----------
W  = 76;      // width
D  = 62;      // depth
H  = 84;      // height
R  = 6;       // radius of the front-to-back edges
t  = 2.4;     // wall thickness
tTop = 3;     // lidar deck thickness

// ---------- Fasteners ----------
insM3_d   = 2.6;   // pilot hole: M3 screws thread-form directly into PETG (no inserts, no soldering iron)
insM3_h   = 8;
m3_clear  = 3.4;
m25_clear = 2.9;
m25_nut   = 5.0;   // M2.5 nut: 5 mm across flats
tripod_nut_af = 11.5;  // standard 1/4"-20 nut: 7/16" = 11.1 mm across flats (+clearance)
tripod_nut_h  = 6.0;   // nut thickness 5.6 mm (+clearance)

clr = 0.4;    // fit clearance around modules

// ---------- Modules ----------
// Seeed MR60BHA2 kit kept in its stock case: 54 x 35 x 22 (radar face = the 54x35 face)
kitW = 54; kitHt = 35; kitDp = 22;
kitX0 = -W/2 + t + 1;            // pushed left -> room for its USB-C plug on the right
kitZ0 = t + 1;
kitCx = kitX0 + (kitW+clr)/2;
kitCz = kitZ0 + (kitHt+clr)/2;

// HLK-LD2450: PCB 44 x 15 x 1.6
ldW = 44; ldHt = 15; ldPcb = 1.6;
ldCz = 48.5;

// XIAO ESP32S3 Sense (with camera board): 21 x 17.8 x 15; USB-C facing right (+X)
xW = 21; xHt = 17.8; xDp = 15;
xCz = 67;

// Lidar (LD19 / STL-19P, same footprint): 54 x 46.29, Ø2.5 holes: pair at ±23.4 in X, single hole 31.92 away in Y
lidY  = 31;          // lidar centre (Y)
lidPairDY = 8.3;     // hole pair 8.3 from centre; single hole 23.6 on the other side
lidSingleDY = -23.6;
slot = 1.2;          // ±1.2 mm slotted tolerance in Y

// Grip
gripA  = 12;     // rake angle (deg)
gripL  = 100;
gripW  = 30;     // X
gripD  = 36;     // Y
gripCy = 44;
flangeD = 38;

// ---------------------------------------------------------------------
module rbox(w, d, h, r) {         // box with rounded edges along Y
    hull() for (x=[-w/2+r, w/2-r], z=[r, h-r])
        translate([x, 0, z]) rotate([-90,0,0]) cylinder(r=r, h=d);
}
module rrect(w, d, r, h) {        // rounded rectangle extruded along Z
    hull() for (x=[-w/2+r, w/2-r], y=[-d/2+r, d/2-r])
        translate([x,y,0]) cylinder(r=r, h=h);
}
module slotY(d, len, h) {         // slot along Y
    hull() for (s=[-len, len]) translate([0,s,0]) cylinder(d=d, h=h);
}
module hexSlotY(af, len, h) {
    hull() for (s=[-len, len]) translate([0,s,0]) cylinder(d=af/cos(30), h=h, $fn=6);
}

// Lidar hole pattern: pair at the front, single hole at the back (turn the lidar 180 deg if needed)
module lidarHoles() {
    for (x=[-23.4, 23.4]) translate([x, lidY - lidPairDY, 0]) children();
    translate([0, lidY - lidSingleDY, 0]) children();
}

bossPos = [for (x=[-W/2+6, W/2-6], z=[6, H-6]) [x, z]];

// =====================================================================
module head() {
    difference() {
        union() {
            difference() {
                rbox(W, D, H, R);
                // interior (open at the back)
                translate([0, t, t]) rbox(W-2*t, D, H-t-tTop, R-t);
            }
            // corner bosses for the back cover
            intersection() {
                rbox(W, D, H, R);
                for (p=bossPos) translate([p[0], D-12, p[1]]) rotate([-90,0,0]) cylinder(d=8.5, h=12);
            }
            // MR60BHA2 kit sleeve (right end open for its USB-C plug)
            intersection() {
                translate([0,0,0]) rbox(W, D, H, R);
                difference() {
                    translate([kitX0-1.6, t-eps, kitZ0-1.6]) cube([kitW+clr+1.6, kitDp, kitHt+clr+3.2]);
                    translate([kitX0, t-1, kitZ0]) cube([kitW+clr+5, kitDp+2, kitHt+clr]);
                }
            }
            // LD2450 frame
            translate([-(ldW+clr)/2-1.2, t-eps, ldCz-(ldHt+clr)/2-1.2])
                difference() {
                    cube([ldW+clr+2.4, 4, ldHt+clr+2.4]);
                    translate([1.2,-1,1.2]) cube([ldW+clr, 6, ldHt+clr]);
                }
            // XIAO / camera pocket
            translate([-(xW+clr)/2-1.2, t-eps, xCz-(xHt+clr)/2-1.2])
                difference() {
                    cube([xW+clr+2.4, xDp, xHt+clr+2.4]);
                    translate([1.2,-1,1.2]) cube([xW+clr, xDp+2, xHt+clr]);
                }
            // reinforcement pads under the lidar deck (nuts)
            lidarHoles() translate([0,0,H-tTop-2.5]) slotY(9, slot, 2.5+eps);
        }
        // ---- front windows ----
        translate([kitCx-25, -1, kitCz-15.5]) cube([50, t+2, 31]);           // radar 60 GHz
        translate([-20.5, -1, ldCz-6]) cube([41, t+2, 12]);                   // radar 24 GHz
        translate([-9, -1, xCz-7.5]) cube([18, t+2, 15]);                     // camera
        // camera chamfer
        hull() {
            translate([-9, t-0.6, xCz-7.5]) cube([18, 0.1, 15]);
            translate([-11, -0.01, xCz-9.5]) cube([22, 0.1, 19]);
        }
        // ---- right side: USB-C cable entry (power bank -> XIAO) ----
        translate([W/2-t-1, t+13.5, xCz]) rotate([0,90,0])
            hull() for (s=[-2.8,2.8]) translate([0,s,0]) cylinder(d=9, h=t+2);
        // ---- lidar deck: M2.5 slots + captive nuts ----
        lidarHoles() {
            translate([0,0,H-tTop-3]) slotY(m25_clear, slot, tTop+4);
            translate([0,0,H-tTop-2.6]) hexSlotY(m25_nut+0.3, slot, 2.2+eps);
        }
        // lidar cable pass-through (rear)
        translate([18, 56, H-tTop-1]) rrect(10, 8, 3, tTop+2);
        // ---- cover screw pilot holes ----
        for (p=bossPos) translate([p[0], D-insM3_h, p[1]]) rotate([-90,0,0]) cylinder(d=insM3_d, h=insM3_h+1);
        // ---- floor: grip screws + wire pass-through ----
        for (y=[gripCy-12, gripCy+12]) translate([0,y,-1]) cylinder(d=m3_clear, h=t+2);
        translate([0, gripCy, -1]) cylinder(d=10, h=t+2);
        // side vents (upper rear)
        for (sx=[-1,1], i=[0:3]) translate([sx*(W/2-t/2), 36+i*6, 60]) cube([t+2, 3, 14], center=true);
    }
}

// =====================================================================
// Back cover (printed outer face down)
colFoam = 3.0;   // gap for a 3 mm adhesive foam pad (absorbs tolerances and components)
// 1.69" ST7789V2 LCD (Waveshare 240x280): PCB 31.5 x 39, glass on the front, 4 brass standoffs on the back
lcdW = 31.5; lcdH = 39.0;
lcdT = 4.5;          // glass + PCB thickness (measure yours)
lcdStand = 4.0;      // height of the brass standoffs on the back
lcdCz = 40;          // screen centre height on the back cover
lcdWinW = 28.6; lcdWinH = 33.4;   // viewing window (active area 27.97 x 32.63)
lcdSx = 13.25;       // standoff centres, X (approx.)

module cover() {
    ct = 2.4;
    lcdBack = D - lcdT;                          // back face of the LCD PCB
    barY = lcdBack - lcdStand - colFoam - 3;     // clamp bars press the standoffs through foam
    difference() {
        union() {
            translate([0, D, 0]) rbox(W, ct, H, R);
            // pressing columns (towards the front)
            // MR60 kit (low, below the screen)
            for (x=[kitCx-13.5, kitCx+13.5])
                translate([x-4, t+clr+kitDp+colFoam, 10-4]) cube([8, D-(t+clr+kitDp+colFoam)+eps, 8]);
            // LD2450 (outside the screen footprint)
            for (x=[-20.5, 20.5])
                translate([x-2.5, t+ldPcb+colFoam+0.4, ldCz-3]) cube([5, D-(t+ldPcb+colFoam+0.4)+eps, 6]);
            // XIAO (above the screen)
            translate([-4, t+xDp+colFoam, xCz-4]) cube([8, D-(t+xDp+colFoam)+eps, 8]);
            // screen pocket walls
            translate([0, 0, lcdCz]) difference() {
                translate([-(lcdW+0.6)/2-1.2, lcdBack, -(lcdH+0.6)/2-1.2]) cube([lcdW+0.6+2.4, lcdT+eps, lcdH+0.6+2.4]);
                translate([-(lcdW+0.6)/2, lcdBack-1, -(lcdH+0.6)/2]) cube([lcdW+0.6, lcdT+2, lcdH+0.6]);
            }
            // screen clamp: two C-brackets (post - bar - post) pressing the standoffs
            for (sx=[-lcdSx, lcdSx]) translate([sx-2.5, 0, lcdCz]) {
                zt = (lcdH+0.6)/2 + 1.2;
                translate([0, barY, -zt-3]) cube([5, 3, 2*zt+6]);                       // bar
                for (zz=[-zt-3, zt]) translate([0, barY, zz]) cube([5, D-barY+eps, 3]); // posts
            }
        }
        // screws
        for (p=bossPos) translate([p[0], D-1, p[1]]) rotate([-90,0,0]) {
            cylinder(d=m3_clear, h=ct+2);
            translate([0,0,ct+1-1.6]) cylinder(d=6.2, h=2);     // counterbore for button head
        }
        // screen window with outer chamfer
        translate([0, D-1, lcdCz]) rotate([-90,0,0]) rrect(lcdWinW, lcdWinH, 2, ct+2);
        hull() {
            translate([0, D+ct-0.8, lcdCz]) rotate([-90,0,0]) rrect(lcdWinW, lcdWinH, 2, 0.01);
            translate([0, D+ct+0.01, lcdCz]) rotate([-90,0,0]) rrect(lcdWinW+2, lcdWinH+2, 3, 0.01);
        }
        // vents
        for (x=[-20,-14,-8,8,14,20]) translate([x-1.5, D-1, 72]) cube([3, ct+2, 8]);
    }
}

// =====================================================================
// Pistol grip (printed upside down: flange on the bed)
module gripBody(w, d, r, zt, zb) {
    hull() {
        translate([0,0,zb]) rrect(w, d, r, eps);
        translate([0,0,zt]) rrect(w, d, r, eps);
    }
}
module grip() {
    difference() {
        union() {
            // flange
            translate([0, gripCy, -4]) rrect(gripW+14, flangeD, 5, 4);
            // raked body
            intersection() {
                translate([0, gripCy, 0]) rotate([gripA,0,0]) union() {
                    gripBody(gripW, gripD, 9, 8, -gripL+6);
                    translate([0,0,-gripL+6]) scale([1,1,0.5]) sphere(d=gripW);  // rounded end (approx.)
                }
                translate([-50,-50,-200]) cube([100, 200, 196]);
            }
            // bosses (screwed from inside the head)
            for (y=[gripCy-12, gripCy+12]) translate([0,y,-12]) cylinder(d=8.5, h=12);
        }
        // inner cavity (sloped ceiling: prints without supports)
        translate([0, gripCy, 0]) rotate([gripA,0,0]) hull() {
            translate([0,0,-14]) rrect(gripW-2*2.4, gripD-2*2.4, 6.6, 0.1);
            translate([0,0,-gripL+22]) rrect(gripW-2*2.4, gripD-2*2.4, 6.6, 0.1);
            translate([0,0,-gripL+10]) rrect(8, 8, 3, 0.1);
        }
        // wire pass-through to the head
        translate([0, gripCy, -20]) cylinder(d=10, h=21);
        // M3 pilot holes
        for (y=[gripCy-12, gripCy+12]) translate([0,y,-10]) cylinder(d=insM3_d, h=11);
        // 1/4"-20 tripod mount: nut slides in through a rear side slot, screw enters from the butt
        translate([0, gripCy, 0]) rotate([gripA,0,0]) {
            translate([0,0,-gripL-10]) cylinder(d=6.8, h=10+5+tripod_nut_h+2);
            translate([0,0,-gripL+5]) hull() {
                rotate([0,0,30]) cylinder(d=tripod_nut_af/cos(30), h=tripod_nut_h, $fn=6);   // flats facing X: the nut cannot spin
                translate([0, 30, 0]) rotate([0,0,30]) cylinder(d=tripod_nut_af/cos(30), h=tripod_nut_h, $fn=6);
            }
        }
        // flat butt
        translate([0, gripCy, 0]) rotate([gripA,0,0]) translate([-50,-50,-gripL-50]) cube([100,100,50]);
    }
}


// =====================================================================
// Desk stand: drop the grip in, the head stays level
dockWall = 3; dockClr = 0.6; dockDepth = 35;
baseW = 110; baseY0 = -10; baseY1 = 115; baseT = 5;
dockZ = -104;     // top of the base plate (below the lowest point of the grip butt: -101.5)
module gripSection(extra, z0, z1) {
    translate([0, gripCy, 0]) rotate([gripA,0,0]) hull() {
        translate([0,0,z0]) rrect(gripW+2*extra, gripD+2*extra, 9+extra, eps);
        translate([0,0,z1]) rrect(gripW+2*extra, gripD+2*extra, 9+extra, eps);
    }
}
module dock() {
    difference() {
        union() {
            // base plate
            translate([0, (baseY0+baseY1)/2, dockZ-baseT]) rrect(baseW, baseY1-baseY0, 10, baseT);
            // cup
            intersection() {
                gripSection(dockClr+dockWall, -gripL-30, -gripL+dockDepth);
                translate([-100,-100,dockZ-baseT]) cube([200,300,200]);
            }
        }
        // grip socket (sloped floor = grip butt)
        gripSection(dockClr, -gripL, -gripL+dockDepth+20);
        // weight-saving pockets under the base
        for (x=[-32,32]) translate([x, 50, dockZ-baseT-1]) rrect(26, 80, 6, baseT-1.2+1);
    }
    // feet: stick 4 silicone bumpers under the base
}

// =====================================================================
// Lidar check template (5 min print): test-fit the lidar BEFORE printing the head
module template() {
    difference() {
        translate([0, lidY, 0]) rrect(62, 58, 4, 2);
        translate([0,0,-1]) lidarHoles() slotY(m25_clear, slot, 4);
        translate([0, lidY, -1]) cylinder(d=4, h=4);        // centre mark
    }
}

// =====================================================================
// Module ghosts (preview only)
module ghostLcd() { color("White", 0.9) translate([-lcdW/2, D-lcdT, lcdCz-lcdH/2]) cube([lcdW, lcdT, lcdH]); }
module ghosts() {
    color("DimGray", 0.8) translate([kitX0+clr/2, t, kitZ0+clr/2]) cube([kitW, kitDp, kitHt]);
    color("SeaGreen", 0.9) translate([-ldW/2, t, ldCz-ldHt/2]) cube([ldW, ldPcb, ldHt]);
    color("RoyalBlue", 0.9) translate([-xW/2, t, xCz-xHt/2]) cube([xW, xDp, xHt]);
    color("Black", 0.9) translate([0, lidY, H]) {
        translate([-38.59/2, -38.59/2, 0]) cube([38.59, 38.59, 22.2]);
        translate([0,0,22.2]) cylinder(d=35.29, h=12.6);
    }
}

// Print-bed orientations (no supports needed)
module print_head()     rotate([90,0,0]) head();                        // front face down
module print_cover()    translate([0,0,D+2.4]) rotate([-90,0,0]) cover(); // outer face down
module print_grip()     rotate([180,0,0]) grip();                        // flange down
module print_dock()     translate([0,0,-(dockZ-baseT)]) dock();          // base plate down

if (PART == "head") head();
else if (PART == "print_head") print_head();
else if (PART == "print_cover") print_cover();
else if (PART == "print_grip") print_grip();
else if (PART == "print_dock") print_dock();
else if (PART == "print_template") template();
else if (PART == "cover") cover();
else if (PART == "grip") grip();
else if (PART == "template") template();
else if (PART == "dock") dock();
else if (PART == "exploded") {
    color("Orange") head();
    color("SlateGray") translate([0,45,0]) cover();
    translate([0,45,0]) ghostLcd();
    color("Orange") translate([0,0,-35]) grip();
    color("SlateGray") translate([0,0,-70]) dock();
    ghosts();
}
else {
    color("Orange") head();
    color("SlateGray") cover();
    color("Orange") grip();
    color("SlateGray") dock();
    ghosts(); ghostLcd();
}
