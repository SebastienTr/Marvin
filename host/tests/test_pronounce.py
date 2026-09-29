# SPDX-License-Identifier: MIT
from marvin_host.voice.tts import pronounce


def test_french_voice_says_the_name_the_english_way():
    assert pronounce("Je m'appelle Marvin.", "fr") == "Je m'appelle Marvine."
    assert pronounce("MARVIN, marvin", "fr") == "Marvine, Marvine"


def test_other_words_and_languages_are_untouched():
    assert pronounce("Marvinesque", "fr") == "Marvinesque"
    assert pronounce("My name is Marvin.", "en") == "My name is Marvin."
