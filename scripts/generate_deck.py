import os
from pptx import Presentation
from pptx.util import Inches, Pt
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE

# ── Color Palette (MD3 dark mode tokens from BouncePay) ─────────────────────
BG_COLOR       = RGBColor(0x12, 0x10, 0x16)  # Deep dark #121016
SURFACE_CARD   = RGBColor(0x1E, 0x1C, 0x24)  # Card #1E1C24
SURFACE_HIGH   = RGBColor(0x29, 0x27, 0x30)  # High container #292730
PRIMARY_PURPLE = RGBColor(0xD0, 0xBC, 0xFF)  # Accent lavender #D0BCFF
TEAL_NET       = RGBColor(0x4F, 0xDB, 0xC7)  # Network teal #4FDBC7
GOLD_ACCENT    = RGBColor(0xF5, 0xC5, 0x42)  # Money gold #F5C542
TEXT_WHITE     = RGBColor(0xFF, 0xFF, 0xFF)
TEXT_MUTED     = RGBColor(0xCA, 0xC4, 0xD0)
TEXT_DIM       = RGBColor(0x93, 0x8F, 0x99)
BORDER_COLOR   = RGBColor(0x3E, 0x3A, 0x46)

FONT_FAMILY = "Segoe UI"

def set_slide_background(slide, prs):
    bg_shape = slide.shapes.add_shape(
        MSO_SHAPE.RECTANGLE, 0, 0, prs.slide_width, prs.slide_height
    )
    bg_shape.fill.solid()
    bg_shape.fill.fore_color.rgb = BG_COLOR
    bg_shape.line.fill.background()
    return bg_shape

def add_header(slide, eyebrow_text, title_text, subtitle_text=None):
    tx_box = slide.shapes.add_textbox(Inches(0.8), Inches(0.4), Inches(11.7), Inches(0.35))
    tf = tx_box.text_frame
    tf.word_wrap = True
    p = tf.paragraphs[0]
    p.text = eyebrow_text.upper()
    p.font.size = Pt(10)
    p.font.bold = True
    p.font.name = FONT_FAMILY
    p.font.color.rgb = PRIMARY_PURPLE

    p2 = tf.add_paragraph()
    p2.text = title_text
    p2.font.size = Pt(23)
    p2.font.bold = True
    p2.font.name = FONT_FAMILY
    p2.font.color.rgb = TEXT_WHITE
    p2.space_before = Pt(3)

    if subtitle_text:
        p3 = tf.add_paragraph()
        p3.text = subtitle_text
        p3.font.size = Pt(12)
        p3.font.name = FONT_FAMILY
        p3.font.color.rgb = TEXT_MUTED
        p3.space_before = Pt(3)

def add_card(slide, left, top, width, height, title, body, badge=None, accent_color=PRIMARY_PURPLE):
    card = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, left, top, width, height)
    card.fill.solid()
    card.fill.fore_color.rgb = SURFACE_CARD
    card.line.color.rgb = BORDER_COLOR
    card.line.width = Pt(1)

    tb = slide.shapes.add_textbox(left + Inches(0.18), top + Inches(0.14), width - Inches(0.36), height - Inches(0.28))
    tf = tb.text_frame
    tf.word_wrap = True

    if badge:
        p0 = tf.paragraphs[0]
        p0.text = badge.upper()
        p0.font.size = Pt(9)
        p0.font.bold = True
        p0.font.name = FONT_FAMILY
        p0.font.color.rgb = accent_color
        p1 = tf.add_paragraph()
    else:
        p1 = tf.paragraphs[0]

    p1.text = title
    p1.font.size = Pt(13)
    p1.font.bold = True
    p1.font.name = FONT_FAMILY
    p1.font.color.rgb = TEXT_WHITE
    p1.space_before = Pt(2)

    p2 = tf.add_paragraph()
    p2.text = body
    p2.font.size = Pt(10)
    p2.font.name = FONT_FAMILY
    p2.font.color.rgb = TEXT_MUTED
    p2.space_before = Pt(4)
    return card

def build_presentation(output_path="BouncePay_iQOO_Final_Deck.pptx"):
    prs = Presentation()
    prs.slide_width = Inches(13.333)
    prs.slide_height = Inches(7.5)
    blank_layout = prs.slide_layouts[6]

    # shared with the website
    img_dir = os.path.abspath("public/images")
    # deck-only artwork: kept out of public/ so it is not deployed with the site
    deck_dir = os.path.abspath("assets/deck")
    img_hero = os.path.join(deck_dir, "hero-mesh.jpg")
    img_venues = os.path.join(img_dir, "crowded-venues.png")
    img_remote = os.path.join(img_dir, "remote-commerce.png")
    img_deadzones = os.path.join(img_dir, "dead-zones.jpg")
    img_iqoo = os.path.join(deck_dir, "iqoo-office-kit.jpg")
    img_security = os.path.join(deck_dir, "zero-trust-security.jpg")
    img_demo = os.path.abspath("public/demo-preview.jpg")

    # ══════════════════════════════════════════════════════════════════════
    # SLIDE 1: COVER / TITLE (WITH HERO 3D MESH VISUAL)
    # ══════════════════════════════════════════════════════════════════════
    s1 = prs.slides.add_slide(blank_layout)
    set_slide_background(s1, prs)

    # Left content column
    badge = s1.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(0.8), Inches(0.9), Inches(5.4), Inches(0.38))
    badge.fill.solid()
    badge.fill.fore_color.rgb = SURFACE_HIGH
    badge.line.color.rgb = PRIMARY_PURPLE
    badge.line.width = Pt(1)
    tf = badge.text_frame
    p = tf.paragraphs[0]
    p.text = "iQOO CITY BATTLES 2026  •  TRACK 01: FINTECH & COMMERCE"
    p.font.size = Pt(9)
    p.font.bold = True
    p.font.color.rgb = PRIMARY_PURPLE
    p.alignment = PP_ALIGN.CENTER

    tb = s1.shapes.add_textbox(Inches(0.8), Inches(1.5), Inches(5.8), Inches(3.6))
    tf = tb.text_frame
    tf.word_wrap = True

    p = tf.paragraphs[0]
    p.text = "BouncePay"
    p.font.size = Pt(50)
    p.font.bold = True
    p.font.name = FONT_FAMILY
    p.font.color.rgb = TEXT_WHITE

    p2 = tf.add_paragraph()
    p2.text = "Payments that bounce until they connect."
    p2.font.size = Pt(22)
    p2.font.bold = True
    p2.font.name = FONT_FAMILY
    p2.font.color.rgb = TEAL_NET
    p2.space_before = Pt(4)

    p3 = tf.add_paragraph()
    p3.text = (
        "An offline peer-to-peer mesh payment protocol.\n"
        "Carries cryptographically signed payments across nearby\n"
        "smartphones over Bluetooth Low Energy until reaching\n"
        "an active cellular gateway for instant settlement."
    )
    p3.font.size = Pt(13)
    p3.font.name = FONT_FAMILY
    p3.font.color.rgb = TEXT_MUTED
    p3.space_before = Pt(10)

    # Right Hero Image
    if os.path.exists(img_hero):
        hero_pic = s1.shapes.add_picture(img_hero, Inches(6.8), Inches(0.9), width=Inches(5.7), height=Inches(4.2))

    # Bottom Metadata Bar
    meta_box = s1.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(0.8), Inches(5.4), Inches(11.7), Inches(1.4))
    meta_box.fill.solid()
    meta_box.fill.fore_color.rgb = SURFACE_CARD
    meta_box.line.color.rgb = BORDER_COLOR
    tf = meta_box.text_frame
    tf.word_wrap = True
    
    p = tf.paragraphs[0]
    p.text = "TEAM & TECHNICAL SPECIFICATION"
    p.font.size = Pt(9)
    p.font.bold = True
    p.font.color.rgb = PRIMARY_PURPLE

    p1 = tf.add_paragraph()
    p1.text = "Builders: Alan James  •  Anuroop Phukan  •  Ishaan Sridharan"
    p1.font.size = Pt(13)
    p1.font.bold = True
    p1.font.color.rgb = TEXT_WHITE
    p1.space_before = Pt(3)

    p2 = tf.add_paragraph()
    p2.text = "Stack: iQOO Flagship Device  •  Snapdragon NPU On-Device AI  •  Qualcomm TEE  •  iQOO Office Kit  •  BLE 5.4 Mesh"
    p2.font.size = Pt(11)
    p2.font.color.rgb = TEAL_NET
    p2.space_before = Pt(3)

    # ══════════════════════════════════════════════════════════════════════
    # SLIDE 2: THE PROBLEM (WITH STAT CALLOUTS)
    # ══════════════════════════════════════════════════════════════════════
    s2 = prs.slides.add_slide(blank_layout)
    set_slide_background(s2, prs)
    add_header(
        s2,
        "The Problem",
        "Where A Missing Bar Of Signal Costs A Sale",
        "Digital payments in India fail every day because cellular connectivity is treated as an all-or-nothing prerequisite."
    )

    # 3 Stat Cards with Big Bold Numbers
    add_card(
        s2, Inches(0.8), Inches(1.85), Inches(3.64), Inches(4.9),
        "Network Congestion Spikes",
        "15% to 20% failure rates occur in high-density Indian environments:\n\n"
        "• Underground metro stations & basements\n"
        "• Packed cricket stadiums, festivals & concerts\n"
        "• Remote rural haats & highway toll booths\n\n"
        "Checkout lines stall, merchants lose immediate revenue, and buyers are forced back into cash.",
        badge="15–20% FAILURE SPIKE",
        accent_color=GOLD_ACCENT
    )

    add_card(
        s2, Inches(4.84), Inches(1.85), Inches(3.64), Inches(4.9),
        "Existing 'Offline' Tech Fails",
        "Current offline solutions fall short of real consumer and merchant needs:\n\n"
        "• USSD (*99#): Painfully slow, 40% timeout rate, poor UX\n"
        "• Offline Wallets: Require pre-locking money ahead of time\n"
        "• Proprietary Soundboxes: Cost ₹2,000+ each, unaffordable for small roadside micro-merchants",
        badge="HIGH FRICTION ALTERNATIVES",
        accent_color=PRIMARY_PURPLE
    )

    add_card(
        s2, Inches(8.88), Inches(1.85), Inches(3.64), Inches(4.9),
        "The 15-Meter Asymmetry",
        "The fundamental paradox of payment failures:\n\n"
        "In 95% of 'dead zones', full 4G/5G connectivity exists just 15 to 30 meters away—someone walking out the door, standing by the stairwell, or near the exit.\n\n"
        "Why let a transaction die when an active gateway is right next to you?",
        badge="THE 15-METER INSIGHT",
        accent_color=TEAL_NET
    )

    # ══════════════════════════════════════════════════════════════════════
    # SLIDE 3: USE-CASE SCENARIOS (WITH THE 3 3D ILLUSTRATIONS!)
    # ══════════════════════════════════════════════════════════════════════
    s3 = prs.slides.add_slide(blank_layout)
    set_slide_background(s3, prs)
    add_header(
        s3,
        "Real-World Scenarios",
        "Built For The Moments Where Connectivity Drops",
        "BouncePay activates seamlessly whenever conventional 4G/5G connections drop."
    )

    scenarios = [
        ("01", "Crowded Venues", "Concerts, stadiums, festivals & expos where 50,000 phones congest the cell towers simultaneously.", img_venues, Inches(0.8)),
        ("02", "Remote Commerce", "Trekking routes, rural markets, high-altitude stalls, and pop-up camps where cellular signal is intermittent.", img_remote, Inches(4.84)),
        ("03", "Connectivity Dead Zones", "Basements, underground metro kiosks, and concrete blind spots where neither party has signal, but someone nearby does.", img_deadzones, Inches(8.88)),
    ]

    card_w = Inches(3.64)
    card_h = Inches(4.9)
    for num, title, desc, img_path, left_pos in scenarios:
        c = s3.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, left_pos, Inches(1.85), card_w, card_h)
        c.fill.solid()
        c.fill.fore_color.rgb = SURFACE_CARD
        c.line.color.rgb = BORDER_COLOR

        if os.path.exists(img_path):
            img_h = Inches(2.2)
            img_w = card_w - Inches(0.3)
            s3.shapes.add_picture(img_path, left_pos + Inches(0.15), Inches(2.0), width=img_w, height=img_h)

        tb = s3.shapes.add_textbox(left_pos + Inches(0.15), Inches(4.35), card_w - Inches(0.3), Inches(2.2))
        tf = tb.text_frame
        tf.word_wrap = True

        p0 = tf.paragraphs[0]
        p0.text = f"{num}  •  {title.upper()}"
        p0.font.size = Pt(11)
        p0.font.bold = True
        p0.font.color.rgb = PRIMARY_PURPLE

        p1 = tf.add_paragraph()
        p1.text = desc
        p1.font.size = Pt(11)
        p1.font.color.rgb = TEXT_MUTED
        p1.space_before = Pt(6)

    # ══════════════════════════════════════════════════════════════════════
    # SLIDE 4: THE SOLUTION (HOW IT WORKS IN 4 PHASES)
    # ══════════════════════════════════════════════════════════════════════
    s4 = prs.slides.add_slide(blank_layout)
    set_slide_background(s4, prs)
    add_header(
        s4,
        "The Solution",
        "How BouncePay Carries The Payment",
        "A zero-trust store-and-forward peer mesh protocol that carries money without needing internet."
    )

    steps = [
        ("Step 1: Offline Creation", "Payer enters amount offline. The iQOO phone signs an encrypted mandate packet using the device's hardware enclave (Ed25519 signature + timestamp + nonce).", PRIMARY_PURPLE),
        ("Step 2: BLE Mesh Hop", "Payment packet broadcasts over Bluetooth Low Energy. Nearby peer 'mule' phones relay the encrypted payload without pairing or identity exposure.", GOLD_ACCENT),
        ("Step 3: Gateway Settle", "The first peer phone encountering cellular/Wi-Fi signal forwards the signed token to the BouncePay gateway / NPCI switch for settlement.", TEAL_NET),
        ("Step 4: Receipt Loop", "Gateway validates signature and balance, completes settlement, and pushes a cryptographic ACK receipt back through the mesh to the merchant.", PRIMARY_PURPLE),
    ]

    col_w = Inches(2.7)
    gap = Inches(0.3)
    start_left = Inches(0.8)

    for i, (stitle, sbody, scolor) in enumerate(steps):
        curr_left = start_left + i * (col_w + gap)
        add_card(
            s4, curr_left, Inches(1.9), col_w, Inches(4.8),
            stitle,
            sbody,
            badge=f"0{i+1} • PHASE",
            accent_color=scolor
        )

    # ══════════════════════════════════════════════════════════════════════
    # SLIDE 5: iQOO FLAGSHIP & SNAPDRAGON NPU (WITH 3D OFFICE KIT RENDER)
    # ══════════════════════════════════════════════════════════════════════
    s5 = prs.slides.add_slide(blank_layout)
    set_slide_background(s5, prs)
    add_header(
        s5,
        "Hardware & Platform Integration",
        "Deep Integration With iQOO Flagship & Snapdragon NPU",
        "Directly satisfies iQOO Hackathon rubrics: On-Device AI (15%), Technical Depth (15%), and Office Kit (10%)."
    )

    # Left: 3D Render Image of iQOO phone + laptop Office Kit + Snapdragon chip
    if os.path.exists(img_iqoo):
        s5.shapes.add_picture(img_iqoo, Inches(0.8), Inches(1.85), width=Inches(5.6), height=Inches(4.9))

    # Right: The 3 Technical Pillars
    add_card(
        s5, Inches(6.7), Inches(1.85), Inches(5.8), Inches(1.5),
        "Snapdragon NPU: On-Device AI",
        "Runs an ultra-lightweight open-source model (TFLite/ONNX) on the NPU to predict the fastest BLE hop path based on RSSI signal gradients & velocity. Also runs on-device offline anomaly scoring.",
        badge="SNAPDRAGON NPU AI • 15% SCORE",
        accent_color=PRIMARY_PURPLE
    )

    add_card(
        s5, Inches(6.7), Inches(3.55), Inches(5.8), Inches(1.5),
        "Qualcomm TEE & Dual-Antenna BLE",
        "Hardware-backed Ed25519 signing inside Qualcomm TEE ensures zero key tampering. Dual-antenna Bluetooth 5.4 radio enables background advertising with <1% battery drain per day.",
        badge="HARDWARE & TEE • 15% SCORE",
        accent_color=TEAL_NET
    )

    add_card(
        s5, Inches(6.7), Inches(5.25), Inches(5.8), Inches(1.5),
        "iQOO Office Kit Bridge",
        "The iQOO phone acts as the mobile POS node, while Office Kit screen-mirroring powers the counter laptop display. Mesh telemetry and receipts bridge via Office Kit clipboard sync.",
        badge="OFFICE KIT BRIDGE • 10% SCORE",
        accent_color=GOLD_ACCENT
    )

    # ══════════════════════════════════════════════════════════════════════
    # SLIDE 6: TECHNICAL ARCHITECTURE (HOW WE BUILD BOUNCEPAY)
    # ══════════════════════════════════════════════════════════════════════
    s_arch = prs.slides.add_slide(blank_layout)
    set_slide_background(s_arch, prs)
    add_header(
        s_arch,
        "Technical Architecture",
        "How We Build BouncePay",
        "Offline transaction → BLE relay → Internet gateway → Backend verification → Settlement"
    )

    # 1. Glowing transaction path track behind nodes
    node_w = Inches(1.56)
    node_h = Inches(1.92)
    node_y = Inches(1.68)
    gap_w = Inches(0.474)
    start_x = Inches(0.8)

    track_y = node_y + Inches(0.82)
    track = s_arch.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0.9), track_y, Inches(11.53), Inches(0.025))
    track.fill.solid()
    track.fill.fore_color.rgb = GOLD_ACCENT
    track.line.fill.background()

    # 2. Six horizontal architecture nodes
    arch_nodes = [
        {
            "label": "[iQOO CUSTOMER]",
            "title": "Android App",
            "sub": "Offline Payment",
            "badge": "🪙 ₹500 Intent",
            "border": PRIMARY_PURPLE,
            "badge_col": GOLD_ACCENT,
        },
        {
            "label": "[RELAY 01]",
            "title": "Encrypted Tx",
            "sub": "Local Queue",
            "badge": "🔒 ₹500 Opaque",
            "border": BORDER_COLOR,
            "badge_col": TEAL_NET,
        },
        {
            "label": "[RELAY 02]",
            "title": "Store & Forward",
            "sub": "Duplicate Check",
            "badge": "🔒 ₹500 Opaque",
            "border": BORDER_COLOR,
            "badge_col": TEAL_NET,
        },
        {
            "label": "[INTERNET GATEWAY]",
            "title": "Connected Peer",
            "sub": "Cellular / Wi-Fi",
            "badge": "📡 ₹500 Uplink",
            "border": TEAL_NET,
            "badge_col": TEAL_NET,
        },
        {
            "label": "[BOUNCEPAY BACKEND]",
            "title": "FastAPI / REST",
            "sub": "Tx Verification",
            "badge": "⚡ Sig Verified",
            "border": PRIMARY_PURPLE,
            "badge_col": PRIMARY_PURPLE,
        },
        {
            "label": "[SIMULATED SETTLEMENT]",
            "title": "Ledger Update",
            "sub": "Receipt ACK",
            "badge": "✅ ₹500 Settled",
            "border": GOLD_ACCENT,
            "badge_col": GOLD_ACCENT,
        }
    ]

    arch_connectors = [
        (["BLE", "GATT"], TEAL_NET),
        (["BLE", "Hop"], TEAL_NET),
        (["Mesh", "Hop"], TEAL_NET),
        (["HTTPS", "Uplink"], GOLD_ACCENT),
        (["REST", "API"], GOLD_ACCENT),
    ]

    for i, n in enumerate(arch_nodes):
        cx = start_x + i * (node_w + gap_w)

        # Outer card
        c = s_arch.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, cx, node_y, node_w, node_h)
        c.fill.solid()
        c.fill.fore_color.rgb = SURFACE_CARD
        c.line.color.rgb = n["border"]
        c.line.width = Pt(1.2 if n["border"] != BORDER_COLOR else 1.0)

        # Text inside card
        tb = s_arch.shapes.add_textbox(cx + Inches(0.06), node_y + Inches(0.08), node_w - Inches(0.12), Inches(1.2))
        tf = tb.text_frame
        tf.word_wrap = True
        tf.margin_top = Pt(2)
        tf.margin_bottom = Pt(2)
        tf.margin_left = Pt(2)
        tf.margin_right = Pt(2)

        p0 = tf.paragraphs[0]
        p0.text = n["label"]
        p0.font.size = Pt(7.5)
        p0.font.bold = True
        p0.font.name = FONT_FAMILY
        p0.font.color.rgb = PRIMARY_PURPLE if i in [0, 4] else (GOLD_ACCENT if i == 5 else TEAL_NET)
        p0.alignment = PP_ALIGN.CENTER

        p1 = tf.add_paragraph()
        p1.text = n["title"]
        p1.font.size = Pt(10)
        p1.font.bold = True
        p1.font.name = FONT_FAMILY
        p1.font.color.rgb = TEXT_WHITE
        p1.space_before = Pt(3)
        p1.alignment = PP_ALIGN.CENTER

        p2 = tf.add_paragraph()
        p2.text = n["sub"]
        p2.font.size = Pt(8.5)
        p2.font.name = FONT_FAMILY
        p2.font.color.rgb = TEXT_MUTED
        p2.space_before = Pt(2)
        p2.alignment = PP_ALIGN.CENTER

        # Dedicated pill for the ₹500 packet tracking status
        badge_w = node_w - Inches(0.20)
        badge_h = Inches(0.30)
        badge_x = cx + Inches(0.10)
        badge_y = node_y + node_h - Inches(0.40)

        bpill = s_arch.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, badge_x, badge_y, badge_w, badge_h)
        bpill.fill.solid()
        bpill.fill.fore_color.rgb = SURFACE_HIGH
        bpill.line.color.rgb = n["badge_col"]
        bpill.line.width = Pt(0.8)

        btb = bpill.text_frame
        btb.word_wrap = False
        btb.margin_top = Pt(0)
        btb.margin_bottom = Pt(0)
        btb.margin_left = Pt(0)
        btb.margin_right = Pt(0)
        bp = btb.paragraphs[0]
        bp.text = n["badge"]
        bp.font.size = Pt(7.5)
        bp.font.bold = True
        bp.font.name = FONT_FAMILY
        bp.font.color.rgb = n["badge_col"]
        bp.alignment = PP_ALIGN.CENTER

        # Connector capsule over the track in gap
        if i < len(arch_connectors):
            conn_lines, conn_col = arch_connectors[i]
            conn_cx = cx + node_w + gap_w / 2

            pill_w = Inches(0.42)
            pill_h = Inches(0.56)
            pill_x = conn_cx - pill_w / 2
            pill_y = track_y - pill_h / 2 + Inches(0.012)

            cpill = s_arch.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, pill_x, pill_y, pill_w, pill_h)
            cpill.fill.solid()
            cpill.fill.fore_color.rgb = SURFACE_HIGH
            cpill.line.color.rgb = BORDER_COLOR
            cpill.line.width = Pt(0.8)

            ctf = cpill.text_frame
            ctf.word_wrap = False
            ctf.margin_top = Pt(2)
            ctf.margin_bottom = Pt(1)
            ctf.margin_left = Pt(1)
            ctf.margin_right = Pt(1)

            cp0 = ctf.paragraphs[0]
            cp0.text = "➔"
            cp0.font.size = Pt(9.5)
            cp0.font.bold = True
            cp0.font.name = FONT_FAMILY
            cp0.font.color.rgb = GOLD_ACCENT
            cp0.alignment = PP_ALIGN.CENTER

            for cline in conn_lines:
                cp = ctf.add_paragraph()
                cp.text = cline
                cp.font.size = Pt(6)
                cp.font.bold = True
                cp.font.name = FONT_FAMILY
                cp.font.color.rgb = conn_col
                cp.alignment = PP_ALIGN.CENTER
                cp.space_before = Pt(0.5)

    # 3. Four compact technology cards below architecture
    tech_cards = [
        {
            "title": "1. MOBILE",
            "accent": PRIMARY_PURPLE,
            "bullets": [
                "Android / Kotlin",
                "Bluetooth Low Energy (BLE)",
                "BLE GATT",
                "Local encrypted storage"
            ]
        },
        {
            "title": "2. SECURITY",
            "accent": TEAL_NET,
            "bullets": [
                "Digital signatures",
                "Encrypted payloads",
                "Nonces + timestamps",
                "Replay / duplicate detection"
            ]
        },
        {
            "title": "3. BACKEND",
            "accent": GOLD_ACCENT,
            "bullets": [
                "Python / FastAPI",
                "REST APIs",
                "PostgreSQL / Supabase",
                "Transaction reconciliation"
            ]
        },
        {
            "title": "4. ON-DEVICE AI",
            "accent": PRIMARY_PURPLE,
            "bullets": [
                "Lightweight ONNX / TFLite model",
                "Relay selection",
                "Anomaly scoring",
                "On-device inference"
            ]
        }
    ]

    tech_y = Inches(3.82)
    tech_h = Inches(1.98)
    tech_w = Inches(2.74)
    tech_gap = Inches(0.25)

    for i, tc in enumerate(tech_cards):
        tx = start_x + i * (tech_w + tech_gap)
        c = s_arch.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, tx, tech_y, tech_w, tech_h)
        c.fill.solid()
        c.fill.fore_color.rgb = SURFACE_CARD
        c.line.color.rgb = BORDER_COLOR
        c.line.width = Pt(1)

        tb = s_arch.shapes.add_textbox(tx + Inches(0.18), tech_y + Inches(0.14), tech_w - Inches(0.36), tech_h - Inches(0.28))
        tf = tb.text_frame
        tf.word_wrap = True
        tf.margin_top = Pt(0)
        tf.margin_bottom = Pt(0)
        tf.margin_left = Pt(0)
        tf.margin_right = Pt(0)

        p0 = tf.paragraphs[0]
        p0.text = tc["title"]
        p0.font.size = Pt(11)
        p0.font.bold = True
        p0.font.name = FONT_FAMILY
        p0.font.color.rgb = tc["accent"]

        for b in tc["bullets"]:
            p = tf.add_paragraph()
            p.text = f"•  {b}"
            p.font.size = Pt(9.5)
            p.font.name = FONT_FAMILY
            p.font.color.rgb = TEXT_MUTED
            p.space_before = Pt(3)

    # 4. Core Flow bottom statement bar
    bot_y = Inches(6.0)
    bot_h = Inches(0.85)
    bot_w = Inches(11.733)

    bot_box = s_arch.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, start_x, bot_y, bot_w, bot_h)
    bot_box.fill.solid()
    bot_box.fill.fore_color.rgb = SURFACE_HIGH
    bot_box.line.color.rgb = BORDER_COLOR
    bot_box.line.width = Pt(1)

    tb = s_arch.shapes.add_textbox(start_x + Inches(0.2), bot_y + Inches(0.08), bot_w - Inches(0.4), bot_h - Inches(0.16))
    tf = tb.text_frame
    tf.word_wrap = True
    tf.margin_top = Pt(0)
    tf.margin_bottom = Pt(0)
    tf.margin_left = Pt(0)
    tf.margin_right = Pt(0)

    p0 = tf.paragraphs[0]
    p0.text = "CORE FLOW"
    p0.font.size = Pt(8.5)
    p0.font.bold = True
    p0.font.name = FONT_FAMILY
    p0.font.color.rgb = PRIMARY_PURPLE
    p0.alignment = PP_ALIGN.CENTER

    p1 = tf.add_paragraph()
    p1.alignment = PP_ALIGN.CENTER
    p1.space_before = Pt(3)

    flow_steps = ["SIGN", "ENCRYPT", "BLE RELAY", "STORE & FORWARD", "GATEWAY", "VERIFY", "SETTLE", "ACK"]
    for idx, st in enumerate(flow_steps):
        r1 = p1.add_run()
        r1.text = st
        r1.font.bold = True
        r1.font.size = Pt(10.5)
        r1.font.name = FONT_FAMILY
        r1.font.color.rgb = TEXT_WHITE
        if idx < len(flow_steps) - 1:
            r2 = p1.add_run()
            r2.text = "  ➔  "
            r2.font.bold = True
            r2.font.size = Pt(10.5)
            r2.font.name = FONT_FAMILY
            r2.font.color.rgb = GOLD_ACCENT

    # ══════════════════════════════════════════════════════════════════════
    # SLIDE 6: 5-LAYER ARCHITECTURAL STACK
    # ══════════════════════════════════════════════════════════════════════
    s6 = prs.slides.add_slide(blank_layout)
    set_slide_background(s6, prs)
    add_header(
        s6,
        "System Architecture",
        "The 5-Layer Stack Behind The Hop",
        "Engineered for sub-100ms discovery, zero trust, and cross-device interoperability."
    )

    layers = [
        ("Layer 1: Device / Application Layer", "Generates transaction payload, executes on-device NPU risk scoring, and signs token in Qualcomm TEE."),
        ("Layer 2: Transport & Discovery (BLE 5.4)", "Uses ephemeral BLE advertisement packets with rotating UUIDs. Zero pairing, zero user taps, minimal radio overhead."),
        ("Layer 3: Store-and-Forward Mesh", "Intermediate 'mule' devices cache encrypted packets in memory with strict TTL. Gossip routing guarantees fast propagation."),
        ("Layer 4: Ingestion Gateway Node", "First node encountering active internet translates BLE payload into secure HTTPS/WebSocket API calls to settlement switch."),
        ("Layer 5: Settlement & NPCI Core", "Verifies merchant & payer public keys, prevents double spending via distributed nonce cache, and settles via UPI Lite rails.")
    ]

    top_y = Inches(1.85)
    layer_h = Inches(0.92)
    for idx, (ltitle, ldesc) in enumerate(layers):
        c = s6.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(0.8), top_y + idx * Inches(1.02), Inches(11.7), layer_h)
        c.fill.solid()
        c.fill.fore_color.rgb = SURFACE_CARD
        c.line.color.rgb = BORDER_COLOR

        tb = s6.shapes.add_textbox(Inches(1.0), top_y + idx * Inches(1.02) + Inches(0.08), Inches(11.3), layer_h - Inches(0.16))
        tf = tb.text_frame
        tf.word_wrap = True

        p = tf.paragraphs[0]
        p.text = ltitle
        p.font.size = Pt(13)
        p.font.bold = True
        p.font.color.rgb = PRIMARY_PURPLE if idx < 3 else TEAL_NET

        p2 = tf.add_paragraph()
        p2.text = ldesc
        p2.font.size = Pt(10.5)
        p2.font.color.rgb = TEXT_MUTED
        p2.space_before = Pt(2)

    # ══════════════════════════════════════════════════════════════════════
    # SLIDE 7: SECURITY & ZERO TRUST (WITH 3D SECURITY VAULT RENDER)
    # ══════════════════════════════════════════════════════════════════════
    s7 = prs.slides.add_slide(blank_layout)
    set_slide_background(s7, prs)
    add_header(
        s7,
        "Security & Cryptography",
        "Zero Trust: The Courier Never Touches The Cash",
        "Intermediate devices are pure relays—they cannot inspect, alter, or steal transactions."
    )

    # Left: 3D Security Image
    if os.path.exists(img_security):
        s7.shapes.add_picture(img_security, Inches(0.8), Inches(1.85), width=Inches(5.4), height=Inches(4.9))

    # Right: 4 Security Cards
    sec_cards = [
        ("End-to-End Encryption", "Payload is encrypted with merchant and banking public keys. Intermediate mule devices see only an opaque binary blob.", "CONFIDENTIALITY", PRIMARY_PURPLE),
        ("Tamper-Proof Signatures", "Every transaction is cryptographically signed with payer's private key. Altering a single byte invalidates the signature.", "INTEGRITY", TEAL_NET),
        ("Anti-Replay & Monotonic Nonces", "Unique monotonic counter + UNIX timestamp window prevents packet sniffing and replay attacks on the banking switch.", "ANTI-REPLAY", GOLD_ACCENT),
        ("Double-Spend Mitigation", "Leverages localized offline balance limits (e.g. ₹500/transaction, ₹2,000 total cumulative offline ceiling).", "FINANCIAL BOUNDS", PRIMARY_PURPLE),
    ]

    right_x = Inches(6.5)
    right_w = Inches(6.0)
    card_h = Inches(1.15)
    for idx, (stitle, sdesc, sbadge, scolor) in enumerate(sec_cards):
        curr_y = Inches(1.85) + idx * Inches(1.25)
        add_card(s7, right_x, curr_y, right_w, card_h, stitle, sdesc, badge=sbadge, accent_color=scolor)

    # ══════════════════════════════════════════════════════════════════════
    # SLIDE 8: PRODUCT DEMO & SIMULATION (WITH LIVE SCREENSHOT)
    # ══════════════════════════════════════════════════════════════════════
    s8 = prs.slides.add_slide(blank_layout)
    set_slide_background(s8, prs)
    add_header(
        s8,
        "Working Prototype",
        "Interactive Protocol Simulator & Real-Time Visualization",
        "Fully functional demo tested on the iQOO flagship phone and mirrored to laptop via Office Kit."
    )

    if os.path.exists(img_demo):
        s8.shapes.add_picture(img_demo, Inches(0.8), Inches(1.85), width=Inches(6.8), height=Inches(4.9))
    
    add_card(
        s8, Inches(7.9), Inches(1.85), Inches(4.6), Inches(4.9),
        "Validated Capabilities",
        "• Phone-First Implementation: Runs smoothly as an offline client on the iQOO flagship phone\n\n"
        "• Visual Mesh Tracking: Dynamic packet tracking showing Bluetooth Low Energy discovery and packet relaying across peer mules\n\n"
        "• Instant Gateway Settlement: Immediate transition from offline hop to settled banking transaction\n\n"
        "• Office Kit Live Mirror: Zero-latency mirroring of the iQOO phone's POS terminal directly onto the pitch laptop\n\n"
        "• Benchmarks: Sub-100ms BLE handoff, <1KB packet payload footprint",
        badge="PROTOTYPE BENCHMARKS",
        accent_color=PRIMARY_PURPLE
    )

    # ══════════════════════════════════════════════════════════════════════
    # SLIDE 9: INCENTIVES & VIABILITY (INDIAN FINTECH CONTEXT)
    # ══════════════════════════════════════════════════════════════════════
    s9 = prs.slides.add_slide(blank_layout)
    set_slide_background(s9, prs)
    add_header(
        s9,
        "Feasibility & Economics",
        "Why Users & Indian FinTech Ecosystems Adopt BouncePay",
        "Aligned with RBI regulations, NPCI UPI Lite, and real merchant behavior in India."
    )

    add_card(
        s9, Inches(0.8), Inches(1.85), Inches(3.64), Inches(4.9),
        "Mule Incentives",
        "Why everyday users run BouncePay:\n\n"
        "• Micro-Cashback: Gateway nodes earn fractional transaction incentives\n"
        "• Silent Daemon: Consumes <1% battery per day on iQOO phones\n"
        "• Mutual Aid: Today your phone relays for a vendor; tomorrow another phone clears your metro ticket",
        badge="USER MOTIVATION",
        accent_color=GOLD_ACCENT
    )

    add_card(
        s9, Inches(4.84), Inches(1.85), Inches(3.64), Inches(4.9),
        "Merchant ROI",
        "Tangible revenue protection for Indian vendors:\n\n"
        "• Zero Hardware Cost: No need to purchase ₹2,000+ proprietary soundboxes\n"
        "• Recovers Lost Sales: Captures 100% of sales currently lost during network dropouts\n"
        "• Reduces Queue Times: Accelerates foot-traffic checkout at concerts, expos & transit",
        badge="MERCHANT ROI",
        accent_color=PRIMARY_PURPLE
    )

    add_card(
        s9, Inches(8.88), Inches(1.85), Inches(3.64), Inches(4.9),
        "Regulatory Alignment",
        "Tailored to Indian payment rails:\n\n"
        "• Fully compliant with RBI Offline Digital Payments & NPCI UPI Lite framework\n"
        "• Compatible with existing UPI QR codes and payment aggregators\n"
        "• Enforces strict ₹500 transaction cap and ₹2,000 offline cumulative limit",
        badge="REGULATORY SCALE",
        accent_color=TEAL_NET
    )

    # ══════════════════════════════════════════════════════════════════════
    # SLIDE 10: HIGH-NOTE CONCLUSION & VISION
    # ══════════════════════════════════════════════════════════════════════
    s10 = prs.slides.add_slide(blank_layout)
    set_slide_background(s10, prs)

    # Top Tag
    badge = s10.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(0.8), Inches(0.8), Inches(3.2), Inches(0.38))
    badge.fill.solid()
    badge.fill.fore_color.rgb = SURFACE_HIGH
    badge.line.color.rgb = PRIMARY_PURPLE
    badge.line.width = Pt(1)
    tf = badge.text_frame
    p = tf.paragraphs[0]
    p.text = "CONCLUSION & VISION"
    p.font.size = Pt(9)
    p.font.bold = True
    p.font.color.rgb = PRIMARY_PURPLE
    p.alignment = PP_ALIGN.CENTER

    # Hero Headline
    tb = s10.shapes.add_textbox(Inches(0.8), Inches(1.3), Inches(11.7), Inches(2.2))
    tf = tb.text_frame
    tf.word_wrap = True

    p = tf.paragraphs[0]
    p.text = "No Transaction Left Behind."
    p.font.size = Pt(46)
    p.font.bold = True
    p.font.name = FONT_FAMILY
    p.font.color.rgb = TEXT_WHITE

    p2 = tf.add_paragraph()
    p2.text = "Where a missing bar of signal costs a sale, BouncePay turns the crowd into the network."
    p2.font.size = Pt(20)
    p2.font.bold = True
    p2.font.name = FONT_FAMILY
    p2.font.color.rgb = TEAL_NET
    p2.space_before = Pt(6)

    # Center Feature Card - The Core Philosophy
    quote_card = s10.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(0.8), Inches(3.2), Inches(11.7), Inches(2.2))
    quote_card.fill.solid()
    quote_card.fill.fore_color.rgb = SURFACE_CARD
    quote_card.line.color.rgb = BORDER_COLOR
    quote_card.line.width = Pt(1)

    tb = s10.shapes.add_textbox(Inches(1.1), Inches(3.4), Inches(11.1), Inches(1.8))
    tf = tb.text_frame
    tf.word_wrap = True

    p = tf.paragraphs[0]
    p.text = "“BouncePay does not create connectivity out of nothing. It borrows connectivity that already exists nearby, and carries the payment toward it — so commerce never stops for a dead zone.”"
    p.font.size = Pt(15.5)
    p.font.italic = True
    p.font.name = FONT_FAMILY
    p.font.color.rgb = TEXT_WHITE

    # 3 High-Impact Pillars below the quote
    p2 = tf.add_paragraph()
    p2.text = "✔ Zero Hardware Cost (Runs on standard phones)      ✔ Zero-Trust Cryptography (Tamper-proof)      ✔ Built for India (NPCI / UPI Lite ready)"
    p2.font.size = Pt(12)
    p2.font.bold = True
    p2.font.name = FONT_FAMILY
    p2.font.color.rgb = GOLD_ACCENT
    p2.space_before = Pt(16)

    # Bottom Sign-Off Bar
    footer_box = s10.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE, Inches(0.8), Inches(5.7), Inches(11.7), Inches(1.1))
    footer_box.fill.solid()
    footer_box.fill.fore_color.rgb = SURFACE_HIGH
    footer_box.line.color.rgb = PRIMARY_PURPLE
    footer_box.line.width = Pt(1)

    tb = footer_box.text_frame
    tb.word_wrap = True

    p = tb.paragraphs[0]
    p.text = "THANK YOU"
    p.font.size = Pt(15)
    p.font.bold = True
    p.font.name = FONT_FAMILY
    p.font.color.rgb = PRIMARY_PURPLE

    p2 = tb.add_paragraph()
    p2.text = "Alan James  •  Anuroop Phukan  •  Ishaan Sridharan   |   GitHub: github.com/71percentbanana/bouncepay   |   iQOO City Battles 2026"
    p2.font.size = Pt(11)
    p2.font.color.rgb = TEXT_MUTED
    p2.space_before = Pt(3)

    try:
        prs.save(output_path)
        print(f"[SUCCESS] Presentation saved to {output_path}")
    except PermissionError:
        print(f"[WARN] Could not save {output_path} (file is open in another program).")

if __name__ == "__main__":
    build_presentation()
