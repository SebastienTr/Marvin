// =====================================================================
//  MARVIN - open-source desk companion, rev F (the upright robot)
//
//  Parametric OpenSCAD source. Units: mm. OpenSCAD >= 2021.01.
//  Frame: X = right, Y = back (+) / front (-, toward the user), Z = up.
//  Z = 0 is the desk. The robot stands upright; the sensors are tilted
//  inside it, not the body.
//
//  This file covers the body half of the robot (everything below the neck).
//  The head (face frame, acrylic window, XIAO cradle, lidar ring, knob)
//  follows once the 1.69" screen is measured; its interface with the body
//  is fixed here (neck bolt pattern, cable hole).
//
//  PART = "assembly" | "exploded" | "section"                (views)
//         "body" | "plinth" | "sled" | "neck" | "grommet" | "foot" | "template"
//         "print_body" | "print_plinth" | "print_sled" | "print_tpu" | "print_template"
//
//  SPDX-License-Identifier: CERN-OHL-P-2.0
// =====================================================================

PART = "assembly";
$fn = 64;
eps = 0.01;

// ---------------- Outer shape -------------------------------------------
bodyW = 80;  bodyD = 74;  bodyR = 16;     // body footprint (squircle), a little narrower than the head
bodyZ0 = 6;  bodyZ1 = 64;                 // body bottom / top (the plinth hides below bodyZ0)
wall = 1.6;                               // half a wavelength at 60 GHz in PETG: the front wall is a radome
deck = 2.0;                               // roof of the body, under the neck
headW = 88;  headD = 80;  headR = 16;     // head footprint (for the ghost and the bolt pattern)
headZ0 = 68; headZ1 = 124;
neckH = headZ0 - bodyZ1;                  // 4 mm TPU gasket between body and head

// Anthracite band: a filament change by layer (cosmetic only; any colour is RF-transparent)
bandZ0 = 20; bandZ1 = 50;

plinthW = 68; plinthD = 62; plinthR = 12; // recessed foot, reads as a shadow line
floorT = 2.0;                             // plinth plate that closes the body from below
floorZ = bodyZ0 + floorT;                 // = 8, everything inside stands on it

// ---------------- Fits and fasteners -------------------------------------
clr = 0.3;
m3_clear = 3.4; m3_pilot = 2.6; m3_head = 6.2; m25_pilot = 2.1;
foam = 3.0;                               // double-sided foam pads under every module

// ---------------- Inside the body ------------------------------------------
inW = bodyW - 2*wall;  inD = bodyD - 2*wall;  inR = bodyR - wall;
yWall  = -inD/2;                          // inner face of the front wall
yFront = yWall + 1.0;                     // module fronts stand 1 mm behind the wall

// 24 GHz radar HLK-LD2450: low, tilted 10 deg up. It sits BELOW the 60 GHz radar so it never
// shadows the 60 GHz beam, which leaves the robot upwards.
ldW = 44; ldH = 15; ldT = 1.6; ldTilt = 10;
ldZ = floorZ + 1.0;                       // bottom edge of the board (rests on a 1 mm lip)

// 60 GHz radar MR60BHA2 kit in Seeed's 54 x 35 x 22 case, landscape, tilted 20 deg up
kitW = 54; kitH = 35; kitD = 22; kitTilt = 20;
kitZ = ldZ + ldH*cos(ldTilt) + 1.5;       // bottom front edge of the case, just above the LD2450

// Sled: four ribs (they carry both radars), a foot and a back plate for the lever connectors and the amp
ribX  = [[14, 20], [23, 27]];             // |x| ranges of the inner and outer ribs
bpT = 3;  bpW = 60;                       // back plate
yBP = inD/2 - clr - bpT;                  // front face of the back plate
sledTop = bodyZ1 - deck - 2;              // 2 mm under the deck
screwX = 17;                              // plinth screws (from below) and back-wall screws (from behind) go into the inner ribs
plinthScrewY = 8;
backScrewZ = [26, 46];

// Speaker 2030 (20 x 30 x 5) glued inside the right wall behind a dot grille
spkY = 0; spkZ = 18; spkL = 30.4; spkH = 20.4;

// Neck: 4 x M3 through TPU grommets. The screws go up into the head floor, so no rigid path
// links the head (lidar motor) to the body (heart-rate radar).
neckBolt = [[-32, -20], [32, -20], [-32, 20], [32, 20]];
gromOD = 7.6; gromFlange = 11; gromFlangeT = 1.6; gromBore = m3_clear;
bossT = 1.2;                              // flat seat for the grommet flange under the deck
cableHole = [30, 16, 5];                  // w, d, corner r; the 90 deg USB-C plug passes through it
cableHoleY = 4;

// Lidar (D800 / D500, LD19 footprint): used by the template now and by the head later
lidPairDY = 8.3; lidSingleDY = -23.6; lidHalfX = 23.4; slot = 1.2; lidY = 0;

// =====================================================================
// Helpers
function rot(v, a) = [v[0]*cos(a) - v[1]*sin(a), v[0]*sin(a) + v[1]*cos(a)];
function add(a, b) = [a[0]+b[0], a[1]+b[1]];
function mul(v, k) = [v[0]*k, v[1]*k];

module squircle(w, d, r) { offset(r=r) square([w - 2*r, d - 2*r], center=true); }
module sqblock(w, d, r, z0, h) { translate([0, 0, z0]) linear_extrude(h) squircle(w, d, r); }
module rrect(w, d, r, h) { linear_extrude(h) offset(r=r) square([w - 2*r, d - 2*r], center=true); }
module slotY(d, len, h) { hull() for (s=[-len, len]) translate([0, s, 0]) cylinder(d=d, h=h, $fn=24); }
module lidarHoles() {
    for (x=[-lidHalfX, lidHalfX]) translate([x, lidY - lidPairDY, 0]) children();
    translate([0, lidY - lidSingleDY, 0]) children();
}
// a 2-D profile drawn in the (y, z) side plane, extruded along X from x0 to x1
module sideExtrude(x0, x1) { translate([x0, 0, 0]) rotate([90, 0, 90]) linear_extrude(x1 - x0) children(); }

// ---------------- Radar geometry in the side plane (y, z) --------------------
// "up" follows the front face of a module tilted by t; "in" goes from its front face into the robot
function upV(t) = [sin(t), cos(t)];
function inV(t) = [cos(t), -sin(t)];
L0 = [yFront, ldZ];                                         // LD2450 front, bottom edge
K0 = [yFront, kitZ];                                        // MR60 case front, bottom edge
kitCentre = add(K0, mul(upV(kitTilt), kitH/2));
ldCentre  = add(L0, mul(upV(ldTilt), ldH/2));
echo(str("MR60BHA2 face centre: y=", kitCentre[0], " z=", kitCentre[1], " | LD2450 face centre: y=", ldCentre[0], " z=", ldCentre[1]));

// Rib profile: lip under the LD2450, foam line behind it, ledge under the MR60 case,
// foam line behind the case, then straight back to the back plate.
function ribProfile() = let(
    sL0 = add(L0, mul(inV(ldTilt), ldT + foam)),            // LD2450 support line, bottom
    sL1 = add(sL0, mul(upV(ldTilt), ldH - 1)),               // ... top
    ledgeZ = function (y) kitZ - (y - yFront) * tan(kitTilt),
    e1 = [sL1[0], ledgeZ(sL1[0])],                           // up to the MR60 ledge
    f1 = add(K0, mul(inV(kitTilt), kitD + foam)),            // end of the ledge (case back + foam)
    f2 = add(f1, mul(upV(kitTilt), kitH - 3))                // top of the case support
) [[yFront, floorZ], [yFront, ldZ - 0.2], [sL0[0], ldZ - 0.2 - (sL0[0]-yFront)*tan(ldTilt)],
   sL1, e1, f1, f2, [yBP + eps, f2[1]], [yBP + eps, floorZ]];

// =====================================================================
// BODY - upright shell with the deck on top. Print upside down (deck on the bed), no supports.
module body() {
    difference() {
        union() {
            difference() {
                sqblock(bodyW, bodyD, bodyR, bodyZ0, bodyZ1 - bodyZ0);
                sqblock(inW, inD, inR, bodyZ0 - eps, bodyZ1 - deck - bodyZ0 + eps);
            }
            // bosses under the deck for the grommet flanges to bear on (keep the deck flat and stiff)
            for (p = neckBolt) translate([p[0], p[1], bodyZ1 - deck - bossT]) cylinder(d=gromFlange + 3, h=bossT + eps);
        }
        // neck bolt holes (grommet shafts) and the cable hole
        for (p = neckBolt) translate([p[0], p[1], bodyZ1 - 5]) cylinder(d=gromOD + 0.4, h=10);
        translate([0, cableHoleY, bodyZ1 - 5]) rrect(cableHole[0], cableHole[1], cableHole[2], 10);
        // back-wall screws into the sled
        for (s=[-1,1], z=backScrewZ) translate([s*screwX, bodyD/2 - wall - 1, z]) rotate([-90, 0, 0]) cylinder(d=m3_clear, h=wall + 2, $fn=24);
        // speaker grille, right side: 6 x 2 dots
        for (i=[0:5], j=[0:1]) translate([bodyW/2 - wall - 1, spkY - 12.5 + i*5, spkZ - 2.5 + j*5]) rotate([0, 90, 0]) cylinder(d=2.0, h=wall + 2, $fn=16);
    }
    // speaker locating rails on the right wall (the speaker drops in from below)
    for (s=[-1,1]) translate([inW/2 - 6, spkY + s*(spkL/2 + clr) + (s > 0 ? 0 : -1.2), spkZ - spkH/2 - 1]) cube([6 + eps, 1.2, spkH + 2]);
}

// PLINTH - recessed foot + floor plate that closes the body. Two M3 screws hold the sled on it.
module plinth() {
    difference() {
        union() {
            sqblock(plinthW, plinthD, plinthR, 0, bodyZ0 + eps);
            sqblock(inW - 2*clr, inD - 2*clr, inR - clr, bodyZ0, floorT);
        }
        for (s=[-1,1]) translate([s*screwX, plinthScrewY, -1]) {
            cylinder(d=m3_clear, h=20, $fn=24);
            cylinder(d=m3_head, h=1 + 3, $fn=32);                          // counterbore from below
        }
        // USB-C cable leaves at the back, hidden under the body overhang
        translate([-4, inD/2 - 14, -1]) cube([8, 20, floorZ + 2]);
        // four shallow pockets for TPU or silicone feet
        for (sx=[-1,1], sy=[-1,1]) translate([sx*(plinthW/2 - 11), sy*(plinthD/2 - 11), -1]) cylinder(d=10.4, h=1 + 1.0);
    }
}

// SLED - carries both radars; the lever connectors and the amp stick on the back plate.
// Stands on the plinth; print upright as installed (foot on the bed), no supports.
module sled() {
    difference() {
        union() {
            for (r = ribX, s = [-1,1]) {
                x0 = s > 0 ? r[0] : -r[1];
                sideExtrude(x0, x0 + (r[1] - r[0])) polygon(ribProfile());
            }
            // back plate
            translate([-bpW/2, yBP, floorZ]) cube([bpW, bpT, sledTop - floorZ]);
            // foot: ties the ribs together behind the 60 GHz case
            translate([-bpW/2 + 3, -8, floorZ]) cube([bpW - 6, yBP - (-8) + eps, 3]);
        }
        // pilot holes: plinth screws from below, back-wall screws from behind
        for (s=[-1,1]) translate([s*screwX, plinthScrewY, floorZ - 1]) cylinder(d=m3_pilot, h=12, $fn=24);
        for (s=[-1,1], z=backScrewZ) translate([s*screwX, yBP + bpT + 1, z]) rotate([90, 0, 0]) cylinder(d=m3_pilot, h=14, $fn=24);
        // cable window in the back plate (open at the top)
        translate([-9, yBP - 1, sledTop - 18]) cube([18, bpT + 2, 20]);
        // keep the sled inside the body with clearance
        difference() {
            translate([-100, -100, 0]) cube([200, 200, 200]);
            sqblock(inW - 2*clr, inD - 2*clr, inR - clr, 0, 200);
        }
    }
}

// NECK - TPU 95A gasket between body and head (the visible dark gap)
module neck() {
    difference() {
        sqblock(bodyW - 10, bodyD - 10, 12, bodyZ1, neckH);
        for (p = neckBolt) translate([p[0], p[1], bodyZ1 - 1]) cylinder(d=gromOD + 0.4, h=neckH + 2);
        translate([0, cableHoleY, bodyZ1 - 1]) rrect(cableHole[0] + 4, cableHole[1] + 4, cableHole[2] + 2, neckH + 2);
    }
}

// GROMMET - TPU top-hat: flange under the deck, shaft through deck and gasket, M3 screw inside
module grommet() {
    difference() {
        union() {
            cylinder(d=gromFlange, h=gromFlangeT);
            cylinder(d=gromOD, h=gromFlangeT + bossT + deck + neckH - 0.5);   // stops 0.5 mm under the head floor
        }
        translate([0, 0, -1]) cylinder(d=gromBore, h=20, $fn=24);
    }
}

module foot() cylinder(d=10, h=2.0);

// Lidar template: print first, check the holes of your lidar
module template() {
    difference() {
        translate([0, lidY, 0]) rrect(62, 58, 4, 2);
        translate([0, 0, -1]) lidarHoles() slotY(2.9, slot, 4);
        translate([0, lidY, -1]) cylinder(d=4, h=4);
    }
}

// =====================================================================
// Ghosts (not printed): modules and the head envelope
module ghosts(cut=false) {
    color("DimGray") translate([-kitW/2, 0, 0]) sideExtrude(0, kitW)
        polygon([K0, add(K0, mul(upV(kitTilt), kitH)), add(add(K0, mul(upV(kitTilt), kitH)), mul(inV(kitTilt), kitD)), add(K0, mul(inV(kitTilt), kitD))]);
    color("SeaGreen") translate([-ldW/2, 0, 0]) sideExtrude(0, ldW)
        polygon([L0, add(L0, mul(upV(ldTilt), ldH)), add(add(L0, mul(upV(ldTilt), ldH)), mul(inV(ldTilt), ldT)), add(L0, mul(inV(ldTilt), ldT))]);
    if (!cut) color("WhiteSmoke", 0.25) sqblock(headW, headD, headR, headZ0, headZ1 - headZ0);
}

// Print orientations
module print_body()     translate([0, 0, bodyZ1]) rotate([180, 0, 0]) body();
module print_plinth()   plinth();
module print_sled()     translate([0, 0, -floorZ]) sled();
module print_template() template();
module print_tpu() {                      // gasket + 4 grommets + 4 feet, one TPU plate
    translate([0, 0, -bodyZ1]) neck();
    for (i=[0:3]) translate([-30 + i*20, 50, 0]) grommet();
    for (i=[0:3]) translate([-30 + i*20, 66, 0]) foot();
}

// cut = true keeps only x < 0, to look at the section from the right
module half(cut) { if (cut) intersection() { children(); translate([-200, -200, -10]) cube([200, 400, 400]); } else children(); }

module assemblyView(e=0, cut=false) {
    color("#6f6c67") half(cut) plinth();
    // body in its three print colours: warm grey, anthracite band, warm grey
    for (b = [[0, bandZ0, "#d9d5cc"], [bandZ0, bandZ1, "#3a3a3c"], [bandZ1, 200, "#d9d5cc"]])
        color(b[2]) half(cut) translate([0, 0, e*1.4]) intersection() { body(); translate([-100, -100, b[0]]) cube([200, 200, b[1] - b[0]]); }
    color("Goldenrod") half(cut) translate([inW/2 - 5, spkY - spkL/2, spkZ - spkH/2 + e*1.4]) cube([5, spkL, spkH]);   // speaker, glued in the body
    color("#c0582b") half(cut) translate([0, 0, e*0.5]) sled();
    color("#222") half(cut) translate([0, 0, e*2.0]) neck();
    color("#222") half(cut) for (p = neckBolt) translate([p[0], p[1], bodyZ1 - deck - bossT - gromFlangeT + e*1.4]) grommet();
    half(cut) translate([0, 0, e*0.5]) ghosts(cut || e > 0);
}

if      (PART == "body")           body();
else if (PART == "plinth")         plinth();
else if (PART == "sled")           sled();
else if (PART == "neck")           neck();
else if (PART == "grommet")        grommet();
else if (PART == "foot")           foot();
else if (PART == "template")       template();
else if (PART == "print_body")     print_body();
else if (PART == "print_plinth")   print_plinth();
else if (PART == "print_sled")     print_sled();
else if (PART == "print_tpu")      print_tpu();
else if (PART == "print_template") print_template();
else if (PART == "exploded")       assemblyView(30);
else if (PART == "section")        assemblyView(0, true);
else                               assemblyView(0);
