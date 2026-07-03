from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.units import cm
from reportlab.lib import colors
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, HRFlowable,
    PageBreak, Table, TableStyle, Image
)
from reportlab.lib.enums import TA_CENTER, TA_LEFT, TA_JUSTIFY
from datetime import date
from PIL import Image as PILImage
import numpy as np
from collections import deque
import tempfile, os

OUTPUT_PATH    = r"C:\Users\ADMIN\Downloads\kinetic-eco-tracker\Kinetic_Eco_Tracker_Terms_and_Privacy_Policy.pdf"
LOGO_PATH_ORIG = r"C:\Users\ADMIN\Downloads\kinetic-eco-tracker\android\app\src\main\res\drawable\ic_app_logo.jpg"

# ── Pre-process logo: flood-fill white background from corners → transparent ──
def make_logo_transparent(src_path: str, threshold: int = 238) -> str:
    """
    BFS flood-fill from the 4 corners removing white/near-white background.
    Returns path to a temp PNG with an alpha channel.
    """
    img  = PILImage.open(src_path).convert("RGBA")
    data = np.array(img, dtype=np.uint8)
    h, w = data.shape[:2]
    visited = np.zeros((h, w), dtype=bool)
    queue   = deque()

    # Seed the flood fill from every edge pixel that is near-white
    for r in range(h):
        for c in [0, w - 1]:
            if not visited[r, c]:
                px = data[r, c]
                if int(px[0]) > threshold and int(px[1]) > threshold and int(px[2]) > threshold:
                    visited[r, c] = True
                    queue.append((r, c))
    for c in range(w):
        for r in [0, h - 1]:
            if not visited[r, c]:
                px = data[r, c]
                if int(px[0]) > threshold and int(px[1]) > threshold and int(px[2]) > threshold:
                    visited[r, c] = True
                    queue.append((r, c))

    while queue:
        r, c = queue.popleft()
        for dr, dc in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            nr, nc = r + dr, c + dc
            if 0 <= nr < h and 0 <= nc < w and not visited[nr, nc]:
                px = data[nr, nc]
                if int(px[0]) > threshold and int(px[1]) > threshold and int(px[2]) > threshold:
                    visited[nr, nc] = True
                    queue.append((nr, nc))

    # Zero out alpha for all background pixels
    data[visited, 3] = 0

    result = PILImage.fromarray(data)
    tmp = tempfile.NamedTemporaryFile(suffix=".png", delete=False)
    result.save(tmp.name, "PNG")
    tmp.close()
    return tmp.name

LOGO_PATH = make_logo_transparent(LOGO_PATH_ORIG)
print(f"Logo prepared: {LOGO_PATH}")

# ── Colour palette ────────────────────────────────────────────────────────────
PRIMARY    = colors.HexColor("#1A7F5A")   # brand green
SECONDARY  = colors.HexColor("#134E3A")   # dark green
ACCENT     = colors.HexColor("#E8F5EF")   # light green tint
RULE_COLOR = colors.HexColor("#B2D8C8")
TEXT_DARK  = colors.HexColor("#1A1A1A")
TEXT_MUTED = colors.HexColor("#555555")
WHITE      = colors.white

EFFECTIVE_DATE = "27 May 2026"
CONTACT_EMAIL  = "kineticecotracker@gmail.com"

# ── Styles ────────────────────────────────────────────────────────────────────
base = getSampleStyleSheet()

def make_style(name, parent="Normal", **kwargs):
    return ParagraphStyle(name, parent=base[parent], **kwargs)

styles = {
    "doc_title": make_style("DocTitle", "Title",
        fontSize=26, textColor=WHITE, alignment=TA_CENTER,
        spaceAfter=4, fontName="Helvetica-Bold"),

    "doc_subtitle": make_style("DocSubtitle",
        fontSize=12, textColor=colors.HexColor("#CCE8DC"),
        alignment=TA_CENTER, spaceAfter=2, fontName="Helvetica"),

    "h1": make_style("H1",
        fontSize=14, textColor=SECONDARY, fontName="Helvetica-Bold",
        spaceBefore=18, spaceAfter=6, borderPad=0),

    "h2": make_style("H2",
        fontSize=11, textColor=PRIMARY, fontName="Helvetica-Bold",
        spaceBefore=12, spaceAfter=4),

    "body": make_style("Body",
        fontSize=9.5, textColor=TEXT_DARK, leading=15,
        alignment=TA_JUSTIFY, spaceAfter=6),

    "bullet": make_style("Bullet",
        fontSize=9.5, textColor=TEXT_DARK, leading=15,
        leftIndent=16, spaceAfter=3, bulletIndent=4),

    "note": make_style("Note",
        fontSize=8.5, textColor=TEXT_MUTED, leading=13,
        leftIndent=12, spaceAfter=4, fontName="Helvetica-Oblique"),

    "footer_text": make_style("FooterText",
        fontSize=8, textColor=TEXT_MUTED, alignment=TA_CENTER),

    "toc_entry": make_style("TocEntry",
        fontSize=9.5, textColor=TEXT_DARK, leading=16),
}

# ── Page template with header / footer ───────────────────────────────────────
def on_page(canvas, doc):
    w, h = A4
    canvas.saveState()
    # Header bar background
    canvas.setFillColor(PRIMARY)
    canvas.rect(0, h - 1.4*cm, w, 1.4*cm, fill=1, stroke=0)
    # Logo in header (small, left-aligned)
    try:
        canvas.drawImage(LOGO_PATH,
                         1.4*cm, h - 1.25*cm,
                         width=1.0*cm, height=1.0*cm,
                         preserveAspectRatio=True, mask='auto')
    except Exception:
        pass
    # Header text
    canvas.setFillColor(WHITE)
    canvas.setFont("Helvetica-Bold", 8.5)
    canvas.drawString(2.7*cm, h - 0.82*cm, "KINETIC ECO TRACKER")
    canvas.setFont("Helvetica", 8)
    canvas.drawRightString(w - 1.5*cm, h - 0.82*cm,
                           "Terms & Conditions | Privacy Policy")
    # Footer
    canvas.setFillColor(TEXT_MUTED)
    canvas.setFont("Helvetica", 7.5)
    canvas.drawCentredString(w/2, 0.7*cm,
        f"Page {doc.page}  |  Effective {EFFECTIVE_DATE}  |  {CONTACT_EMAIL}")
    canvas.setStrokeColor(RULE_COLOR)
    canvas.setLineWidth(0.5)
    canvas.line(1.5*cm, 1.1*cm, w - 1.5*cm, 1.1*cm)
    canvas.restoreState()

def on_first_page(canvas, doc):
    """Cover page — hero block with logo centred."""
    w, h = A4
    canvas.saveState()
    # Hero block
    canvas.setFillColor(PRIMARY)
    canvas.rect(0, h - 8.5*cm, w, 8.5*cm, fill=1, stroke=0)
    # Decorative circles
    canvas.setFillColor(colors.HexColor("#15694A"))
    canvas.circle(w - 1.5*cm, h - 1.0*cm, 4.0*cm, fill=1, stroke=0)
    canvas.circle(1.0*cm, h - 6.5*cm, 2.5*cm, fill=1, stroke=0)
    # Logo centred in hero block
    logo_size = 3.2*cm
    logo_x = (w - logo_size) / 2
    logo_y = h - 4.0*cm
    try:
        canvas.drawImage(LOGO_PATH,
                         logo_x, logo_y,
                         width=logo_size, height=logo_size,
                         preserveAspectRatio=True, mask='auto')
    except Exception:
        pass
    # Footer
    canvas.setFillColor(TEXT_MUTED)
    canvas.setFont("Helvetica", 7.5)
    canvas.drawCentredString(w/2, 0.7*cm,
        f"Page {doc.page}  |  Effective {EFFECTIVE_DATE}  |  {CONTACT_EMAIL}")
    canvas.setStrokeColor(RULE_COLOR)
    canvas.setLineWidth(0.5)
    canvas.line(1.5*cm, 1.1*cm, w - 1.5*cm, 1.1*cm)
    canvas.restoreState()

# ── Helper: numbered section heading ─────────────────────────────────────────
def section(number, title):
    return [
        HRFlowable(width="100%", thickness=1, color=RULE_COLOR,
                   spaceAfter=4, spaceBefore=8),
        Paragraph(f"{number}.&nbsp;&nbsp;{title}", styles["h1"]),
    ]

def sub(title):
    return Paragraph(title, styles["h2"])

def body(text):
    return Paragraph(text, styles["body"])

def bullet(text):
    return Paragraph(f"&#8226;&nbsp;&nbsp;{text}", styles["bullet"])

def note(text):
    return Paragraph(f"<i>{text}</i>", styles["note"])

def space(h=6):
    return Spacer(1, h)

# ── Content ───────────────────────────────────────────────────────────────────
story = []

# ── Cover ─────────────────────────────────────────────────────────────────────
# Logo is drawn directly on canvas by on_first_page; leave space for it + title block
story.append(Spacer(1, 4.5*cm))   # clears the logo (3.2 cm) + some breathing room
story.append(Paragraph("KINETIC ECO TRACKER", styles["doc_title"]))
story.append(Paragraph("Terms &amp; Conditions and Privacy Policy", styles["doc_subtitle"]))
story.append(Paragraph(f"Effective Date: {EFFECTIVE_DATE}", styles["doc_subtitle"]))
story.append(Spacer(1, 4.2*cm))

# Info box
info_data = [
    ["Document", "Terms & Conditions and Privacy Policy"],
    ["App Name", "Kinetic Eco Tracker"],
    ["Platform", "Android (Google Play) &amp; Web (PWA)"],
    ["Contact", CONTACT_EMAIL],
    ["Effective", EFFECTIVE_DATE],
]
info_table = Table(info_data, colWidths=[4*cm, 11.5*cm])
info_table.setStyle(TableStyle([
    ("BACKGROUND",   (0, 0), (0, -1), ACCENT),
    ("BACKGROUND",   (1, 0), (1, -1), WHITE),
    ("TEXTCOLOR",    (0, 0), (0, -1), SECONDARY),
    ("FONTNAME",     (0, 0), (0, -1), "Helvetica-Bold"),
    ("FONTNAME",     (1, 0), (1, -1), "Helvetica"),
    ("FONTSIZE",     (0, 0), (-1, -1), 9),
    ("ROWBACKGROUNDS", (0, 0), (-1, -1), [ACCENT, WHITE]),
    ("GRID",         (0, 0), (-1, -1), 0.5, RULE_COLOR),
    ("VALIGN",       (0, 0), (-1, -1), "MIDDLE"),
    ("LEFTPADDING",  (0, 0), (-1, -1), 10),
    ("RIGHTPADDING", (0, 0), (-1, -1), 10),
    ("TOPPADDING",   (0, 0), (-1, -1), 6),
    ("BOTTOMPADDING",(0, 0), (-1, -1), 6),
]))
story.append(info_table)
story.append(space(12))
story.append(body(
    "Please read this document carefully before using Kinetic Eco Tracker. "
    "By downloading, installing, or using the application you agree to be bound "
    "by these Terms &amp; Conditions and Privacy Policy. If you do not agree, "
    "do not use the application."
))
story.append(PageBreak())

# ── Table of Contents ─────────────────────────────────────────────────────────
story.append(Paragraph("Table of Contents", styles["h1"]))
story.append(HRFlowable(width="100%", thickness=1.5, color=PRIMARY, spaceAfter=8))
toc = [
    ("1", "Agreement to Terms"),
    ("2", "Description of Service"),
    ("3", "Eligibility"),
    ("4", "User Accounts"),
    ("5", "Data We Collect"),
    ("6", "How We Use Your Data"),
    ("7", "Data Sharing &amp; Third-Party Services"),
    ("8", "Android Permissions"),
    ("9", "Advertising (Google AdMob)"),
    ("10", "AI-Powered Features"),
    ("11", "Leaderboard &amp; Social Features"),
    ("12", "Data Retention &amp; Deletion"),
    ("13", "Your Rights (GDPR / CCPA)"),
    ("14", "Children's Privacy (COPPA)"),
    ("15", "Security"),
    ("16", "Disclaimers &amp; Limitation of Liability"),
    ("17", "Intellectual Property"),
    ("18", "Changes to this Document"),
    ("19", "Governing Law"),
    ("20", "Contact Us"),
]
for num, title in toc:
    story.append(Paragraph(f"<b>{num}.</b>&nbsp;&nbsp;{title}", styles["toc_entry"]))
story.append(PageBreak())

# ── 1. Agreement to Terms ─────────────────────────────────────────────────────
story += section("1", "Agreement to Terms")
story.append(body(
    "These Terms &amp; Conditions ('Terms') and Privacy Policy ('Policy') constitute a "
    "legally binding agreement between you ('User', 'you') and the developer of "
    "Kinetic Eco Tracker ('we', 'us', 'our'). By accessing or using the application "
    "on any platform, you confirm that you have read, understood, and agree to be bound "
    "by these Terms."
))
story.append(body(
    "We reserve the right to update these Terms at any time. Continued use of the "
    "application after changes are published constitutes acceptance of the revised Terms."
))

# ── 2. Description of Service ─────────────────────────────────────────────────
story += section("2", "Description of Service")
story.append(body(
    "Kinetic Eco Tracker is a motion and activity tracking application that:"
))
for item in [
    "Detects and classifies physical activities including walking, running, cycling, "
    "driving, electric vehicle travel, motorcycle riding, train travel, and air travel.",
    "Calculates estimated CO2 emissions or savings compared to a standard petrol vehicle "
    "baseline, based on distance travelled and activity type.",
    "Estimates calories burned during tracked activities.",
    "Records GPS-based distance and speed.",
    "Provides an AI-powered eco insight feature using session data.",
    "Offers a leaderboard allowing opt-in comparison of eco metrics with other users.",
    "Delivers daily and weekly push notification summaries of tracked activity.",
]:
    story.append(bullet(item))
story.append(space())
story.append(note(
    "Disclaimer: All calculations (CO2, calories, distance) are estimates based on "
    "published reference values and should not be relied upon for scientific, medical, "
    "or regulatory purposes."
))

# ── 3. Eligibility ────────────────────────────────────────────────────────────
story += section("3", "Eligibility")
story.append(body(
    "You must be at least 13 years of age to use this application. If you are between "
    "13 and 18 years of age, you may only use the application with the consent and "
    "supervision of a parent or legal guardian. Users under 13 are strictly prohibited "
    "from using this application in accordance with the Children's Online Privacy "
    "Protection Act (COPPA) and equivalent regulations."
))
story.append(body(
    "By using the application, you represent and warrant that you meet these eligibility "
    "requirements."
))

# ── 4. User Accounts ──────────────────────────────────────────────────────────
story += section("4", "User Accounts")
story.append(sub("4.1  Registration"))
story.append(body(
    "Accounts are created and managed via Firebase Authentication (Google LLC). You may "
    "register using an email address and password, or via a supported social login "
    "provider (e.g. Google Sign-In). You are responsible for maintaining the "
    "confidentiality of your credentials and for all activity that occurs under your account."
))
story.append(sub("4.2  Account Termination"))
story.append(body(
    "We reserve the right to suspend or terminate accounts that violate these Terms, "
    "engage in fraudulent activity, or submit falsified tracking data to the leaderboard. "
    "You may delete your account at any time from the application settings, which will "
    "trigger deletion of your personal data as described in Section 12."
))

# ── 5. Data We Collect ────────────────────────────────────────────────────────
story += section("5", "Data We Collect")
story.append(body(
    "We collect the minimum data necessary to provide the tracking and eco-insight "
    "features. The categories of data collected are:"
))

story.append(sub("5.1  Location Data"))
story.append(body(
    "The application collects precise GPS location data (latitude, longitude, altitude, "
    "speed, and accuracy) while a tracking session is active. Background location access "
    "is requested solely to continue tracking when the application is not in the foreground. "
    "Location data is processed on-device first; session summaries (not raw GPS traces) "
    "are stored in the cloud."
))
story.append(note(
    "Google Play Data Safety disclosure: Location — Precise location, collected during "
    "active use and optionally in the background; used for app functionality; "
    "not sold to third parties."
))

story.append(sub("5.2  Motion &amp; Sensor Data"))
story.append(body(
    "The application accesses the accelerometer, gyroscope, and step counter to "
    "improve activity classification accuracy. Raw sensor streams are processed in "
    "real-time on the device only; individual sensor readings are never transmitted "
    "to our servers."
))

story.append(sub("5.3  Physical Profile (Optional)"))
story.append(body(
    "You may optionally provide height, weight, age, and sex to improve calorie "
    "estimates. This data is stored in your Firebase user document, accessible only "
    "to you, and is never shared with third parties."
))

story.append(sub("5.4  Session Statistics"))
story.append(body(
    "Aggregated session statistics — including total distance, duration, activity "
    "breakdown, estimated CO2, and calories — are stored in Firebase Firestore "
    "under your user account."
))

story.append(sub("5.5  Account Data"))
story.append(body(
    "Your email address, account creation date, and last login timestamp are stored "
    "for authentication and account management purposes."
))

story.append(sub("5.6  Device &amp; Usage Data"))
story.append(body(
    "Firebase Analytics and Crashlytics may collect anonymised diagnostic data "
    "including device model, OS version, app version, and crash reports. This data "
    "is governed by Google's privacy policy."
))

story.append(sub("5.7  Push Notification Tokens"))
story.append(body(
    "If you grant notification permission, a Firebase Cloud Messaging (FCM) token "
    "is stored in your user record to enable personalised daily and weekly summaries. "
    "You may revoke notification permission at any time via device settings."
))

# ── 6. How We Use Your Data ───────────────────────────────────────────────────
story += section("6", "How We Use Your Data")
for item in [
    "<b>Provide core functionality</b> — activity detection, distance calculation, "
    "CO2 and calorie estimation.",
    "<b>Improve accuracy</b> — sensor fusion and Kalman filtering applied on-device "
    "to reduce GPS noise.",
    "<b>AI-powered insights</b> — anonymised session statistics are sent to a "
    "Firebase Cloud Function which queries the Gemini API to generate eco tips. "
    "No personally identifiable information is included in the AI request.",
    "<b>Leaderboard aggregation</b> — if you opt in, your aggregated eco metrics "
    "are published to the leaderboard.",
    "<b>Push notifications</b> — daily and weekly summaries are sent between "
    "18:00–20:00 local time (daily) and Sunday 09:00–11:00 local time (weekly).",
    "<b>Analytics &amp; stability</b> — anonymised crash and usage data helps us "
    "identify and fix bugs.",
]:
    story.append(bullet(item))

# ── 7. Data Sharing & Third-Party Services ────────────────────────────────────
story += section("7", "Data Sharing &amp; Third-Party Services")
story.append(body(
    "We do not sell your personal data. We share data only with the following "
    "service providers, solely to operate the application:"
))

services = [
    ["Service", "Provider", "Purpose", "Data Shared"],
    ["Firebase Auth", "Google LLC", "Authentication", "Email, UID"],
    ["Cloud Firestore", "Google LLC", "Data storage", "Session stats, profile"],
    ["Cloud Functions", "Google LLC", "AI processing", "Anonymised session stats"],
    ["Firebase Messaging", "Google LLC", "Push notifications", "FCM token"],
    ["Gemini API", "Google LLC", "AI insights", "Anonymised session stats"],
    ["Google AdMob", "Google LLC", "Advertising", "Ad identifier (opt-out available)"],
    ["Firebase Analytics", "Google LLC", "App analytics", "Anonymised usage data"],
    ["Firebase Crashlytics", "Google LLC", "Crash reporting", "Anonymised crash logs"],
    ["Overpass API (OSM)", "OpenStreetMap", "Railway detection", "GPS coordinates (transient)"],
]
svc_table = Table(services, colWidths=[3.5*cm, 3*cm, 4*cm, 5*cm])
svc_table.setStyle(TableStyle([
    ("BACKGROUND",   (0, 0), (-1, 0), SECONDARY),
    ("TEXTCOLOR",    (0, 0), (-1, 0), WHITE),
    ("FONTNAME",     (0, 0), (-1, 0), "Helvetica-Bold"),
    ("FONTNAME",     (0, 1), (-1, -1), "Helvetica"),
    ("FONTSIZE",     (0, 0), (-1, -1), 8),
    ("ROWBACKGROUNDS", (0, 1), (-1, -1), [WHITE, ACCENT]),
    ("GRID",         (0, 0), (-1, -1), 0.5, RULE_COLOR),
    ("VALIGN",       (0, 0), (-1, -1), "MIDDLE"),
    ("LEFTPADDING",  (0, 0), (-1, -1), 6),
    ("RIGHTPADDING", (0, 0), (-1, -1), 6),
    ("TOPPADDING",   (0, 0), (-1, -1), 5),
    ("BOTTOMPADDING",(0, 0), (-1, -1), 5),
]))
story.append(svc_table)
story.append(space(8))
story.append(body(
    "All third-party services are governed by Google's Privacy Policy available at "
    "https://policies.google.com/privacy. GPS coordinates sent to the Overpass API "
    "for railway corridor detection are transient, not logged, and subject to the "
    "OpenStreetMap Foundation's privacy policy."
))

# ── 8. Android Permissions ────────────────────────────────────────────────────
story += section("8", "Android Permissions")
story.append(body(
    "The application requests the following Android permissions. All permissions "
    "serve a specific functional purpose:"
))

perms = [
    ["Permission", "Purpose"],
    ["ACCESS_FINE_LOCATION", "GPS tracking during active sessions"],
    ["ACCESS_COARSE_LOCATION", "Fallback positioning when GPS is unavailable"],
    ["ACCESS_BACKGROUND_LOCATION", "Continue tracking when app is backgrounded"],
    ["ACTIVITY_RECOGNITION", "Google's Activity Recognition API for auto-start"],
    ["BODY_SENSORS", "Step counter and accelerometer for improved detection"],
    ["FOREGROUND_SERVICE / FOREGROUND_SERVICE_LOCATION",
     "Maintain tracking service while app is in foreground"],
    ["POST_NOTIFICATIONS", "Daily/weekly eco summary push notifications"],
    ["INTERNET / ACCESS_NETWORK_STATE", "Firebase sync and AI insights"],
    ["RECEIVE_BOOT_COMPLETED",
     "Re-register activity transitions after device restart (auto-start feature)"],
    ["CAMERA", "Profile photo capture (optional)"],
    ["READ/WRITE_EXTERNAL_STORAGE (API ≤ 32)",
     "Export session reports on older Android versions"],
]
perm_table = Table(perms, colWidths=[7*cm, 8.5*cm])
perm_table.setStyle(TableStyle([
    ("BACKGROUND",   (0, 0), (-1, 0), SECONDARY),
    ("TEXTCOLOR",    (0, 0), (-1, 0), WHITE),
    ("FONTNAME",     (0, 0), (-1, 0), "Helvetica-Bold"),
    ("FONTNAME",     (0, 1), (-1, -1), "Helvetica"),
    ("FONTSIZE",     (0, 0), (-1, -1), 8),
    ("ROWBACKGROUNDS", (0, 1), (-1, -1), [WHITE, ACCENT]),
    ("GRID",         (0, 0), (-1, -1), 0.5, RULE_COLOR),
    ("VALIGN",       (0, 0), (-1, -1), "MIDDLE"),
    ("LEFTPADDING",  (0, 0), (-1, -1), 6),
    ("RIGHTPADDING", (0, 0), (-1, -1), 6),
    ("TOPPADDING",   (0, 0), (-1, -1), 5),
    ("BOTTOMPADDING",(0, 0), (-1, -1), 5),
    ("WORDWRAP",     (0, 0), (-1, -1), True),
]))
story.append(perm_table)

# ── 9. Advertising ────────────────────────────────────────────────────────────
story += section("9", "Advertising (Google AdMob)")
story.append(body(
    "Kinetic Eco Tracker displays advertisements served by Google AdMob. AdMob may "
    "use your advertising identifier (Android Ad ID) to serve personalised ads. "
    "You may opt out of personalised advertising at any time:"
))
for item in [
    "Android: Settings &rarr; Google &rarr; Ads &rarr; Opt out of Ads Personalisation.",
    "Alternatively, reset your Advertising ID in the same menu.",
]:
    story.append(bullet(item))
story.append(space())
story.append(body(
    "Ad revenue supports continued development of the application. We do not share "
    "personally identifiable information with AdMob beyond what is automatically "
    "collected by the SDK. AdMob's data use is governed by Google's Privacy Policy."
))

# ── 10. AI-Powered Features ───────────────────────────────────────────────────
story += section("10", "AI-Powered Features")
story.append(body(
    "The Eco Insight feature sends anonymised session statistics (total duration, "
    "distance, activity breakdown, calorie estimate, CO2 estimate) to a Firebase "
    "Cloud Function. The Cloud Function calls the Google Gemini API on your behalf "
    "to generate a personalised eco tip. "
))
story.append(body(
    "No personally identifiable information (name, email, location coordinates) is "
    "included in the data sent to Gemini. AI-generated insights are for informational "
    "purposes only and do not constitute professional health, environmental, or "
    "financial advice."
))
story.append(body(
    "Rate limiting applies: AI insights are limited per user per day to ensure "
    "fair usage and service availability."
))

# ── 11. Leaderboard & Social Features ────────────────────────────────────────
story += section("11", "Leaderboard &amp; Social Features")
story.append(body(
    "Participation in the leaderboard is entirely optional and requires explicit "
    "opt-in. When you opt in:"
))
for item in [
    "Your aggregated eco metrics (CO2 saved, distance, session count) are written "
    "to a public leaderboard collection in Firestore visible to other authenticated users.",
    "Your display name or email prefix may be visible to other users on the leaderboard.",
    "You may opt out and remove your entry at any time from the application settings.",
    "Leaderboard data is validated server-side to prevent submission of "
    "fraudulent or physically implausible values.",
]:
    story.append(bullet(item))

# ── 12. Data Retention & Deletion ────────────────────────────────────────────
story += section("12", "Data Retention &amp; Deletion")
story.append(body(
    "Session data is retained for as long as your account is active. "
    "You may delete individual sessions from within the application at any time. "
    "To delete your entire account and all associated data:"
))
for item in [
    "Navigate to Profile &rarr; Settings &rarr; Delete Account.",
    "Confirm deletion. All Firestore documents, session history, FCM tokens, "
    "leaderboard entries, and physical profile data will be permanently deleted.",
    "Firebase Authentication credentials will be removed.",
    "Deletion is irreversible. Data cannot be recovered once the process is complete.",
]:
    story.append(bullet(item))
story.append(space())
story.append(body(
    "Anonymised, aggregated analytics data (crash logs, usage events) collected by "
    "Firebase Crashlytics and Analytics may be retained as per Google's data "
    "retention policies, as this data cannot be attributed to an individual."
))

# ── 13. Your Rights ───────────────────────────────────────────────────────────
story += section("13", "Your Rights (GDPR / CCPA)")
story.append(body(
    "Depending on your jurisdiction, you may have the following rights regarding "
    "your personal data:"
))

rights = [
    ("<b>Right of Access</b>", "Request a copy of the personal data we hold about you."),
    ("<b>Right to Rectification</b>", "Request correction of inaccurate data."),
    ("<b>Right to Erasure</b>", "Request deletion of your personal data (see Section 12)."),
    ("<b>Right to Restriction</b>", "Request that we limit processing of your data."),
    ("<b>Right to Data Portability</b>",
     "Request your session data in a machine-readable format."),
    ("<b>Right to Object</b>",
     "Object to processing for direct marketing (opt out of leaderboard)."),
    ("<b>CCPA — Right to Know</b>",
     "California residents may request details of personal information collected."),
    ("<b>CCPA — Right to Non-Discrimination</b>",
     "Exercising your rights will not result in degraded service."),
]
for right, desc in rights:
    story.append(bullet(f"{right} — {desc}"))

story.append(space())
story.append(body(
    f"To exercise any of these rights, contact us at {CONTACT_EMAIL}. "
    "We will respond within 30 days."
))

# ── 14. Children's Privacy ────────────────────────────────────────────────────
story += section("14", "Children's Privacy (COPPA)")
story.append(body(
    "Kinetic Eco Tracker is not directed at children under 13 years of age. "
    "We do not knowingly collect personal information from children under 13. "
    "If we become aware that a child under 13 has provided personal information "
    "without verifiable parental consent, we will delete such information immediately."
))
story.append(body(
    "Users aged 13 to 17 may use the application only with parental or guardian "
    "consent. If you are a parent or guardian and believe your child has created "
    f"an account without your consent, please contact us at {CONTACT_EMAIL}."
))
story.append(note(
    "Google Play App Content Rating: This application is rated for users aged 13+ "
    "and is classified under the Health &amp; Fitness category."
))

# ── 15. Security ──────────────────────────────────────────────────────────────
story += section("15", "Security")
story.append(body(
    "We implement the following technical safeguards to protect your data:"
))
for item in [
    "All data in transit is encrypted using HTTPS/TLS.",
    "Firestore security rules enforce that users can only read and write their own data.",
    "API keys are stored server-side and never exposed in client-side code.",
    "Android app backup is disabled to prevent extraction of session data via ADB.",
    "Firebase Authentication is used for all user identity verification.",
    "Rate limiting is applied to AI features to prevent abuse.",
]:
    story.append(bullet(item))
story.append(space())
story.append(body(
    "No method of electronic storage or transmission is 100% secure. While we strive "
    "to use commercially acceptable means to protect your data, we cannot guarantee "
    "absolute security. In the event of a data breach affecting your rights, we will "
    "notify affected users as required by applicable law."
))

# ── 16. Disclaimers & Limitation of Liability ────────────────────────────────
story += section("16", "Disclaimers &amp; Limitation of Liability")
story.append(sub("16.1  No Warranty"))
story.append(body(
    'THE APPLICATION IS PROVIDED "AS IS" AND "AS AVAILABLE" WITHOUT WARRANTIES OF ANY KIND, '
    "EXPRESS OR IMPLIED. WE DO NOT WARRANT THAT THE APPLICATION WILL BE UNINTERRUPTED, "
    "ERROR-FREE, OR THAT ACTIVITY CLASSIFICATIONS, DISTANCE, CALORIE, OR CO2 CALCULATIONS "
    "WILL BE ACCURATE."
))
story.append(sub("16.2  Limitation of Liability"))
story.append(body(
    "TO THE MAXIMUM EXTENT PERMITTED BY LAW, WE SHALL NOT BE LIABLE FOR ANY INDIRECT, "
    "INCIDENTAL, SPECIAL, CONSEQUENTIAL, OR PUNITIVE DAMAGES ARISING FROM YOUR USE OF "
    "THE APPLICATION, INCLUDING BUT NOT LIMITED TO LOSS OF DATA, PERSONAL INJURY, "
    "OR PROPERTY DAMAGE."
))
story.append(sub("16.3  Health Disclaimer"))
story.append(body(
    "Calorie and activity data provided by this application is estimated and is not a "
    "substitute for professional medical advice. Consult a healthcare professional "
    "before making health or fitness decisions based on data from this application."
))

# ── 17. Intellectual Property ─────────────────────────────────────────────────
story += section("17", "Intellectual Property")
story.append(body(
    "All intellectual property rights in the application, including but not limited "
    "to the source code, design, branding, and documentation, are owned by the "
    "developer of Kinetic Eco Tracker. You are granted a limited, non-exclusive, "
    "non-transferable licence to use the application for personal, non-commercial purposes."
))
story.append(body(
    "You may not reproduce, distribute, modify, create derivative works of, publicly "
    "display, or commercially exploit any part of the application without our prior "
    "written consent."
))

# ── 18. Changes to this Document ─────────────────────────────────────────────
story += section("18", "Changes to this Document")
story.append(body(
    "We reserve the right to modify these Terms and Policy at any time. When changes "
    "are made, we will update the Effective Date at the top of this document and, for "
    "material changes, notify you via an in-app notification or email. Your continued "
    "use of the application after the effective date of the revised document constitutes "
    "your acceptance of the changes."
))

# ── 19. Governing Law ─────────────────────────────────────────────────────────
story += section("19", "Governing Law")
story.append(body(
    "These Terms are governed by and construed in accordance with the laws of the "
    "jurisdiction in which the developer is registered, without regard to its conflict "
    "of law provisions. Any disputes arising under these Terms shall be subject to "
    "the exclusive jurisdiction of the courts of that jurisdiction."
))
story.append(body(
    "For users in the European Union, nothing in these Terms affects your statutory "
    "rights under applicable EU consumer protection or data protection law (GDPR)."
))

# ── 20. Contact Us ────────────────────────────────────────────────────────────
story += section("20", "Contact Us")
story.append(body(
    "For questions, data requests, or complaints regarding this document or the "
    "handling of your personal data, please contact us:"
))
contact_data = [
    ["Email", CONTACT_EMAIL],
    ["Subject line", "Kinetic Eco Tracker — Privacy / Terms Enquiry"],
    ["Response time", "Within 30 business days"],
]
contact_table = Table(contact_data, colWidths=[4*cm, 11.5*cm])
contact_table.setStyle(TableStyle([
    ("BACKGROUND",   (0, 0), (0, -1), ACCENT),
    ("BACKGROUND",   (1, 0), (1, -1), WHITE),
    ("TEXTCOLOR",    (0, 0), (0, -1), SECONDARY),
    ("FONTNAME",     (0, 0), (0, -1), "Helvetica-Bold"),
    ("FONTNAME",     (1, 0), (1, -1), "Helvetica"),
    ("FONTSIZE",     (0, 0), (-1, -1), 9),
    ("GRID",         (0, 0), (-1, -1), 0.5, RULE_COLOR),
    ("VALIGN",       (0, 0), (-1, -1), "MIDDLE"),
    ("LEFTPADDING",  (0, 0), (-1, -1), 10),
    ("RIGHTPADDING", (0, 0), (-1, -1), 10),
    ("TOPPADDING",   (0, 0), (-1, -1), 7),
    ("BOTTOMPADDING",(0, 0), (-1, -1), 7),
]))
story.append(contact_table)
story.append(space(16))
story.append(HRFlowable(width="100%", thickness=1.5, color=PRIMARY, spaceAfter=10))
story.append(Paragraph(
    f"&copy; {date.today().year} Kinetic Eco Tracker. All rights reserved. "
    f"Effective {EFFECTIVE_DATE}.",
    styles["footer_text"]
))

# ── Build ─────────────────────────────────────────────────────────────────────
doc = SimpleDocTemplate(
    OUTPUT_PATH,
    pagesize=A4,
    leftMargin=1.8*cm,
    rightMargin=1.8*cm,
    topMargin=2.0*cm,
    bottomMargin=1.8*cm,
    title="Kinetic Eco Tracker — Terms & Conditions and Privacy Policy",
    author="Kinetic Eco Tracker",
    subject="Terms of Service and Privacy Policy",
)
doc.build(story, onFirstPage=on_first_page, onLaterPages=on_page)
print(f"PDF created: {OUTPUT_PATH}")

# Clean up temp logo file
try:
    os.unlink(LOGO_PATH)
except Exception:
    pass
