from pathlib import Path

import pytest

from mailscan.parser import html_to_text, parse_message

PROTO = Path(__file__).resolve().parent.parent / "prototype"
FIXTURES = sorted((PROTO / "emails").glob("*.eml")) + sorted((PROTO / "emails_work").glob("*.eml"))


def parse(name, folder="emails", **kw):
    return parse_message((PROTO / folder / name).read_bytes(), **kw)


def test_both_fixture_sets_present():
    assert len(FIXTURES) == 40


@pytest.mark.parametrize("path", FIXTURES, ids=lambda p: f"{p.parent.name}/{p.name}")
def test_every_fixture_parses(path):
    m = parse_message(path.read_bytes())
    assert "@" in m.from_addr
    assert m.subject and m.body
    assert "=?" not in m.subject + m.from_name  # no undecoded encoded-words left


def test_multiword_encoded_display_names():
    m = parse("20_ru_bank_phishing.eml")
    assert m.from_name == "Банк Север — Служба безопасности"
    assert m.from_addr == "security@banksever-online-verify.example"
    assert parse("06_el_school_trip.eml").from_name == "Δημοτικό Σχολείο Αγίας Ειρήνης"
    assert parse("14_el_building_water.eml").from_name == "Διαχείριση Πολυκατοικίας"


def test_base64_and_quoted_printable_bodies():
    assert "Β΄ τάξη" in parse("06_el_school_trip.eml").body
    assert "Постановление" in parse("05_ru_traffic_fine.eml").body


def test_html_only_drops_script_and_style():
    body = parse("15_en_shop_promo.eml").body
    assert "Black Friday is here" in body
    assert "dataLayer" not in body and ".btn" not in body


def test_hidden_prompt_injection_never_reaches_the_model():
    body = parse("17_ru_injection_promo.eml").body
    assert "Кешбэк 15%" in body
    assert "ИИ-АССИСТЕНТ" not in body and "importance" not in body


def test_hidden_block_with_unclosed_children():
    text = html_to_text('<div style="display: none"><p>secret<p>more</div><p>visible</p>')
    assert "secret" not in text and "more" not in text and "visible" in text


def test_other_hiding_tricks():
    text = html_to_text('<span hidden>a1</span><span style="visibility:hidden">a2</span>'
                        '<span style="font-size:0px">a3</span><span style="font-size:0.9em">keep</span>')
    assert text.strip() == "keep"


def test_body_is_truncated():
    assert len(parse("17_nl_meeting_notes_long.eml", "emails_work", body_max_chars=100).body) == 100


def test_sender():
    assert parse("09_en_friend_trip.eml").sender == "Dimitris K. <dimitris.k@mailbox.example>"
    assert parse("01_ru_bank_charge.eml").message_id == "<txn-20261103-074205-4417@banksever.example>"
