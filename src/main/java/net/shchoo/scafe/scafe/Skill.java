package net.shchoo.scafe.scafe;

import java.util.ArrayList;
import java.util.List;

public class Skill {

    // xp needed for level = (10th root 2) * totalXP + baseXP
    // baseXp set to 1047 because:
    // level 99 is "13 mil" (13.9)
    // level 99-100 is first level which takes > 1mil xp to reach
    public static final long[] XP_PER_LEVEL = {
            0,       1047,    2249,    3538,    4919,     6399,     7985,     9685,     11507,    13460,
            15554,   17798,   20203,   22781,   25544,    28505,    31678,    35079,    38724,    42631,
            46819,   51307,   56117,   61273,   66799,    72721,    79068,    85871,    93162,    100977,
            109353,  118330,  127951,  138263,  149315,   161160,   173855,   187461,   202044,   217674,
            234426,  252380,  271622,  292246,  314350,   338040,   363431,   390644,   419810,   451070,
            484574,  520482,  558967,  600215,  644423,   691804,   742586,   797013,   855346,   917866,
            984874,  1056691, 1133662, 1216158, 1304575,  1399338,  1500903,  1609757,  1726424,  1851465,
            1985481, 2129115, 2283058, 2448051, 2624886,  2814413,  3017543,  3235252,  3468587,  3718669,
            3986701, 4273970, 4581857, 4911843, 5265513,  5644567,  6050827,  6486246,  6952916,  7453081,
            7989145, 8563684, 9179459, 9839431, 10546771, 11304879, 12117400, 12988238, 13921579, 14921909
    };
    public Skill(String name, SkillType type) {
        this.type = type;
        this.name = name;
        this.productLines = new ArrayList<>();
    }

    public Skill(SkillType type, String name, List<ProductLine> productLines) {
        this.type = type;
        this.name = name;
        this.productLines = productLines;
    }

    public Skill(String name, List<ProductLine> productLines)
    {
        this.name = name;
        this.productLines = productLines;
        this.type = SkillType.TERTIARY; // todo bad default -- calculate from PL.
    }

    SkillType type;
    String name;
    public List<ProductLine> productLines;



    public SkillType getType() { return type; }
}
