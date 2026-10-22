#!/usr/bin/env python3
"""Insert voice-command strings into every locale. Drafts stay marked in their files."""
from pathlib import Path

ROOT = Path("/workspace/app/src/main/res")

ABOUT = (
    "Speech. Wake-word listening uses Vosk, the small English model "
    "vosk-model-small-en-us-0.15, by Alpha Cephei. The library and the model are "
    "Apache License 2.0. Your voice is not sent off the phone. The model is English, "
    "so the words it hears reliably are the English command words. "
    "Pictures. The walking model is trained from openly licensed sets. "
    "ROD obstacle pictures: Amirkabir University of Technology, Zandi and colleagues, MIT, "
    "from Roboflow sets that are mostly CC BY 4.0. "
    "RDD2022 India potholes: Arya and colleagues, Geoscience Data Journal 2024, CC BY 4.0. "
    "Open Images stairs, street lights, carts, and cattle: annotations CC BY 4.0, "
    "Flickr photos CC BY 2.0. "
    "Pothole sets from Roboflow, CC BY 4.0. "
    "Tuk-tuk and rickshaw pictures via shravya11, CC BY 4.0. "
    "Datacluster Labs auto-rickshaw sample, CC BY 4.0 on the dataset card. "
    "AssistEye road obstacles: MIT for the collection. Some upstream pictures are CC0 or CC BY 4.0. "
    "Some curb pictures carry stock-photo watermarks and are not freely reusable. "
    "Speed bump and low branch were dropped because those folders had no pictures. "
    "Curb pictures are few, so curbs are easy to miss. "
    "This app is an extra to a white cane. It is not a replacement."
)

EN = {
    "wake_on": "Voice commands: on",
    "wake_off": "Voice commands: off",
    "cd_wake_turn_on": "Voice commands are off. Double tap to turn them on.",
    "cd_wake_turn_off": "Voice commands are on. Double tap to turn them off.",
    "wake_phrase_label": "Wake phrase",
    "cd_wake_phrase": "Wake phrase. The words you say before a command. The default is Hey Guide.",
    "wake_phrase_hint": "Hey Guide",
    "confirm_wake_phrase": "Wake phrase set to %1$s.",
    "wake_phrase_rejected": "Those words are not in the offline English model. The wake phrase was not changed.",
    "prompt_allow_microphone": "Your phone will ask to use the microphone. Please allow it. Obstacle Guide listens on the phone for the wake phrase, then for one command. It does not send your voice to the internet. You can turn this off in settings.",
    "status_mic_needed": "The microphone is off. Turn voice commands on and allow the microphone.",
    "status_mic_denied": "Microphone permission was not allowed. Voice commands need the microphone.",
    "status_mic_blocked": "Microphone permission is blocked. Open settings, then Permissions, and turn the microphone on.",
    "status_mic_unavailable": "The offline speech model could not be opened. Close Obstacle Guide and open it again.",
    "status_mic_busy": "The microphone is being used by another app. Close that app, then turn voice commands on again.",
    "status_voice_off": "Voice commands are off.",
    "status_spotting": "Waiting for %1$s.",
    "status_capturing": "Listening for one command.",
    "wake_ack": "Yes?",
    "confirm_started_already": "Obstacle warnings are already on.",
    "confirm_stopped_already": "Obstacle warnings are already off.",
    "confirm_repeat": "Repeating.",
    "confirm_nothing_to_repeat": "There is no warning to repeat yet.",
    "confirm_louder": "Louder.",
    "confirm_quieter": "Quieter.",
    "confirm_louder_max": "Speech is already at the loudest.",
    "confirm_quieter_min": "Speech is already at the quietest.",
    "confirm_faster": "Faster.",
    "confirm_slower": "Slower.",
    "confirm_faster_max": "Speech is already at the fastest.",
    "confirm_slower_min": "Speech is already at the slowest.",
    "confirm_language": "Language changed.",
    "confirm_unknown": "That was not a command. Say help for the list.",
    "ahead_clear": "Nothing is in front of you right now.",
    "help_commands": "After %1$s, say one command: start, stop, pause, what is in front of me, repeat, louder, quieter, faster, slower, change language, or help. You can also shake the phone hard, double-tap the screen when TalkBack is off, or hold a volume key while this screen is open.",
    "cmd_start": "start|start guide|begin",
    "cmd_stop": "stop|pause|stop guide",
    "cmd_ahead": "what is in front of me|what is ahead|what is in front",
    "cmd_repeat": "repeat|say again|repeat that",
    "cmd_louder": "louder|speak louder|volume up",
    "cmd_quieter": "quieter|speak quieter|softer|volume down",
    "cmd_faster": "faster|speak faster",
    "cmd_slower": "slower|speak slower",
    "cmd_language": "change language|next language",
    "cmd_help": "help|list commands|what can i say",
    "voice_off_action": "Turn voice off",
    "voice_ear_note": "Listening stays on the phone. Nothing is uploaded. Say Hey Guide, then one command.",
    "about_button": "About and licenses",
    "cd_about": "Open the list of speech and picture licenses",
    "about_title": "About",
    "about_intro": "Obstacle Guide is an extra to a white cane. It is not a replacement.",
    "about_body": ABOUT,
    "action_back": "Back",
    "cd_back": "Go back to the main screen",
}

# Drafts. Command lists keep the English words the bundled model can hear,
# and add local words for the parser. Native-speaker review is still required.
HI = {
    **{k: v for k, v in EN.items() if k.startswith("cmd_") or k in ("about_body", "wake_phrase_hint")},
    "wake_on": "आवाज़ आदेश: चालू",
    "wake_off": "आवाज़ आदेश: बंद",
    "cd_wake_turn_on": "आवाज़ आदेश बंद हैं। चालू करने के लिए दो बार टैप करें।",
    "cd_wake_turn_off": "आवाज़ आदेश चालू हैं। बंद करने के लिए दो बार टैप करें।",
    "wake_phrase_label": "जगाने वाले शब्द",
    "cd_wake_phrase": "जगाने वाले शब्द। आदेश से पहले ये बोलें। मूल शब्द Hey Guide हैं।",
    "confirm_wake_phrase": "जगाने वाले शब्द अब %1$s हैं।",
    "wake_phrase_rejected": "ये शब्द ऑफ़लाइन अंग्रेज़ी मॉडल में नहीं हैं। जगाने वाले शब्द नहीं बदले।",
    "prompt_allow_microphone": "फ़ोन माइक्रोफ़ोन की अनुमति माँगेगा। कृपया अनुमति दें। Obstacle Guide फ़ोन पर जगाने वाले शब्द सुनता है, फिर एक आदेश। आवाज़ इंटरनेट पर नहीं जाती। इसे सेटिंग में बंद कर सकते हैं।",
    "status_mic_needed": "माइक्रोफ़ोन बंद है। आवाज़ आदेश चालू करें और माइक्रोफ़ोन की अनुमति दें।",
    "status_mic_denied": "माइक्रोफ़ोन की अनुमति नहीं मिली। आवाज़ आदेश के लिए माइक्रोफ़ोन चाहिए।",
    "status_mic_blocked": "माइक्रोफ़ोन की अनुमति रुकी है। सेटिंग, फिर अनुमतियाँ खोलें, और माइक्रोफ़ोन चालू करें।",
    "status_mic_unavailable": "ऑफ़लाइन बोल पहचान नहीं खुली। Obstacle Guide बंद करके फिर खोलें।",
    "status_mic_busy": "माइक्रोफ़ोन किसी और ऐप के पास है। वह ऐप बंद करें, फिर आवाज़ आदेश चालू करें।",
    "status_voice_off": "आवाज़ आदेश बंद हैं।",
    "status_spotting": "%1$s का इंतज़ार है।",
    "status_capturing": "एक आदेश सुना जा रहा है।",
    "wake_ack": "हाँ?",
    "confirm_started_already": "बाधा चेतावनी पहले से चालू है।",
    "confirm_stopped_already": "बाधा चेतावनी पहले से बंद है।",
    "confirm_repeat": "फिर से कह रहा हूँ।",
    "confirm_nothing_to_repeat": "अभी दोहराने के लिए कोई चेतावनी नहीं है।",
    "confirm_louder": "आवाज़ बढ़ाई।",
    "confirm_quieter": "आवाज़ घटाई।",
    "confirm_louder_max": "आवाज़ पहले से सबसे तेज़ है।",
    "confirm_quieter_min": "आवाज़ पहले से सबसे धीमी है।",
    "confirm_faster": "बोलना तेज़ किया।",
    "confirm_slower": "बोलना धीमा किया।",
    "confirm_faster_max": "बोलना पहले से सबसे तेज़ है।",
    "confirm_slower_min": "बोलना पहले से सबसे धीमा है।",
    "confirm_language": "भाषा बदल गई।",
    "confirm_unknown": "यह आदेश नहीं था। सूची के लिए help बोलें।",
    "ahead_clear": "अभी सामने कुछ नहीं दिखा।",
    "help_commands": "%1$s के बाद एक अंग्रेज़ी आदेश बोलें: start, stop, pause, what is in front of me, repeat, louder, quieter, faster, slower, change language, या help। फ़ोन ज़ोर से हिलाएँ, TalkBack बंद हो तो स्क्रीन पर दो बार टैप करें, या यह स्क्रीन खुली हो तो वॉल्यूम कुंजी दबाकर रखें।",
    "cmd_start": "शुरू|शुरू करो|start|start guide|begin",
    "cmd_stop": "रुको|बंद करो|stop|pause|stop guide",
    "cmd_ahead": "सामने क्या है|मेरे सामने क्या है|what is in front of me|what is ahead|what is in front",
    "cmd_repeat": "दोहराओ|फिर से बोलो|repeat|say again|repeat that",
    "cmd_louder": "जोर से|आवाज़ बढ़ाओ|louder|speak louder|volume up",
    "cmd_quieter": "आवाज़ कम करो|धीरे|quieter|speak quieter|softer|volume down",
    "cmd_faster": "तेज़ बोलो|faster|speak faster",
    "cmd_slower": "धीरे बोलो|slower|speak slower",
    "cmd_language": "भाषा बदलो|change language|next language",
    "cmd_help": "मदद|आदेश बताओ|help|list commands|what can i say",
    "voice_off_action": "आवाज़ बंद करें",
    "voice_ear_note": "सुनने वाला मॉडल अंग्रेज़ी है। Hey Guide बोलें, फिर अंग्रेज़ी आदेश। आवाज़ फ़ोन पर रहती है।",
    "about_button": "परिचय और लाइसेंस",
    "cd_about": "बोलने और तस्वीरों के लाइसेंस खोलें",
    "about_title": "परिचय",
    "about_intro": "बाधा मार्गदर्शक सफेद छड़ी के साथ है, उसकी जगह नहीं।",
    "action_back": "वापस",
    "cd_back": "मुख्य स्क्रीन पर वापस जाएँ",
}

TA = {
    "wake_on": "குரல் கட்டளை: இயக்கம்",
    "wake_off": "குரல் கட்டளை: நிறுத்தம்",
    "cd_wake_turn_on": "குரல் கட்டளை நிறுத்தத்தில் உள்ளது. இயக்க இரண்டு முறை தட்டவும்.",
    "cd_wake_turn_off": "குரல் கட்டளை இயக்கத்தில் உள்ளது. நிறுத்த இரண்டு முறை தட்டவும்.",
    "wake_phrase_label": "எழுப்பும் சொல்",
    "cd_wake_phrase": "எழுப்பும் சொல். கட்டளைக்கு முன் இதைச் சொல்லுங்கள். இயல்பு Hey Guide.",
    "confirm_wake_phrase": "எழுப்பும் சொல் இப்போது %1$s.",
    "wake_phrase_rejected": "இந்தச் சொற்கள் ஆஃப்லைன் ஆங்கில மாதிரியில் இல்லை. எழுப்பும் சொல் மாறவில்லை.",
    "prompt_allow_microphone": "தொலைபேசி மைக்ரோஃபோன் அனுமதி கேட்கும். அனுமதிக்கவும். Obstacle Guide தொலைபேசியில் எழுப்பும் சொல்லைக் கேட்கும், பிறகு ஒரு கட்டளை. குரல் இணையத்துக்குப் போகாது. அமைப்பில் இதை அணைக்கலாம்.",
    "status_mic_needed": "மைக்ரோஃபோன் அணைந்துள்ளது. குரல் கட்டளையை இயக்கி மைக்ரோஃபோனை அனுமதிக்கவும்.",
    "status_mic_denied": "மைக்ரோஃபோன் அனுமதி கிடைக்கவில்லை. குரல் கட்டளைக்கு மைக்ரோஃபோன் வேண்டும்.",
    "status_mic_blocked": "மைக்ரோஃபோன் அனுமதி தடுக்கப்பட்டது. அமைப்பு, பிறகு அனுமதிகள், மைக்ரோஃபோனை இயக்கவும்.",
    "status_mic_unavailable": "ஆஃப்லைன் பேச்சு மாதிரி திறக்கவில்லை. Obstacle Guide மூடி மீண்டும் திறக்கவும்.",
    "status_mic_busy": "மைக்ரோஃபோனை வேறு செயலி பயன்படுத்துகிறது. அதை மூடி, குரல் கட்டளையை மீண்டும் இயக்கவும்.",
    "status_voice_off": "குரல் கட்டளை அணைந்துள்ளது.",
    "status_spotting": "%1$s காத்திருக்கிறது.",
    "status_capturing": "ஒரு கட்டளை கேட்கப்படுகிறது.",
    "wake_ack": "சொல்லுங்கள்?",
    "confirm_started_already": "தடை எச்சரிக்கை ஏற்கெனவே இயங்குகிறது.",
    "confirm_stopped_already": "தடை எச்சரிக்கை ஏற்கெனவே நின்றுள்ளது.",
    "confirm_repeat": "மீண்டும் சொல்கிறேன்.",
    "confirm_nothing_to_repeat": "மீண்டும் சொல்ல எச்சரிக்கை இல்லை.",
    "confirm_louder": "ஒலி கூட்டப்பட்டது.",
    "confirm_quieter": "ஒலி குறைக்கப்பட்டது.",
    "confirm_louder_max": "ஒலி ஏற்கெனவே அதிகபட்சம்.",
    "confirm_quieter_min": "ஒலி ஏற்கெனவே குறைந்தபட்சம்.",
    "confirm_faster": "பேச்சு வேகமானது.",
    "confirm_slower": "பேச்சு மெதுவானது.",
    "confirm_faster_max": "பேச்சு ஏற்கெனவே மிக வேகம்.",
    "confirm_slower_min": "பேச்சு ஏற்கெனவே மிக மெதுவு.",
    "confirm_language": "மொழி மாற்றப்பட்டது.",
    "confirm_unknown": "அது கட்டளை அல்ல. பட்டியலுக்கு help சொல்லுங்கள்.",
    "ahead_clear": "இப்போது முன்னால் ஒன்றும் இல்லை.",
    "help_commands": "%1$s பிறகு ஒரு ஆங்கில கட்டளை சொல்லுங்கள்: start, stop, pause, what is in front of me, repeat, louder, quieter, faster, slower, change language, அல்லது help. தொலைபேசியை வேகமாக குலுக்கவும், TalkBack அணைந்திருந்தால் திரையில் இரண்டு முறை தட்டவும், அல்லது இந்தத் திரை திறந்திருக்கும்போது ஒலி பொத்தானை அழுத்திப் பிடிக்கவும்.",
    "cmd_start": "தொடங்கு|start|start guide|begin",
    "cmd_stop": "நிறுத்து|stop|pause|stop guide",
    "cmd_ahead": "முன்னால் என்ன|what is in front of me|what is ahead|what is in front",
    "cmd_repeat": "மீண்டும் சொல்|repeat|say again|repeat that",
    "cmd_louder": "சத்தமாக|louder|speak louder|volume up",
    "cmd_quieter": "மெதுவாக|quieter|speak quieter|softer|volume down",
    "cmd_faster": "வேகமாகப் பேசு|faster|speak faster",
    "cmd_slower": "மெதுவாகப் பேசு|slower|speak slower",
    "cmd_language": "மொழி மாற்று|change language|next language",
    "cmd_help": "உதவி|help|list commands|what can i say",
    "voice_off_action": "குரலை அணை",
    "voice_ear_note": "கேட்கும் மாதிரி ஆங்கிலம். Hey Guide சொல்லி, பிறகு ஆங்கில கட்டளை. குரல் தொலைபேசியிலேயே இருக்கும்.",
    "about_button": "அறிமுகமும் உரிமங்களும்",
    "cd_about": "பேச்சு மற்றும் பட உரிமங்களைத் திறக்கவும்",
    "about_title": "அறிமுகம்",
    "about_intro": "தடை வழிகாட்டி வெள்ளை கோலுக்குத் துணை. அதற்குப் பதிலாக அல்ல.",
    "action_back": "பின்செல்",
    "cd_back": "முதன்மை திரைக்குத் திரும்பு",
}

BN = {
    "wake_on": "কণ্ঠস্বর নির্দেশ: চালু",
    "wake_off": "কণ্ঠস্বর নির্দেশ: বন্ধ",
    "cd_wake_turn_on": "কণ্ঠস্বর নির্দেশ বন্ধ। চালু করতে দুবার চাপুন।",
    "cd_wake_turn_off": "কণ্ঠস্বর নির্দেশ চালু। বন্ধ করতে দুবার চাপুন।",
    "wake_phrase_label": "জাগানোর কথা",
    "cd_wake_phrase": "জাগানোর কথা। নির্দেশের আগে এটা বলুন। প্রাথমিক কথা Hey Guide।",
    "confirm_wake_phrase": "জাগানোর কথা এখন %1$s।",
    "wake_phrase_rejected": "এই শব্দ অফলাইন ইংরেজি মডেলে নেই। জাগানোর কথা বদলায়নি।",
    "prompt_allow_microphone": "ফোন মাইক্রোফোনের অনুমতি চাইবে। দয়া করে দিন। Obstacle Guide ফোনে জাগানোর কথা শোনে, তারপর একটি নির্দেশ। কণ্ঠস্বর ইন্টারনেটে যায় না। সেটিংসে এটা বন্ধ করা যায়।",
    "status_mic_needed": "মাইক্রোফোন বন্ধ। কণ্ঠস্বর নির্দেশ চালু করুন এবং মাইক্রোফোনের অনুমতি দিন।",
    "status_mic_denied": "মাইক্রোফোনের অনুমতি মেলেনি। কণ্ঠস্বর নির্দেশের জন্য মাইক্রোফোন লাগে।",
    "status_mic_blocked": "মাইক্রোফোনের অনুমতি আটকানো। সেটিংস, তারপর অনুমতি, মাইক্রোফোন চালু করুন।",
    "status_mic_unavailable": "অফলাইন কথা চেনা খোলেনি। Obstacle Guide বন্ধ করে আবার খুলুন।",
    "status_mic_busy": "মাইক্রোফোন অন্য অ্যাপে। সেটা বন্ধ করুন, তারপর কণ্ঠস্বর নির্দেশ চালু করুন।",
    "status_voice_off": "কণ্ঠস্বর নির্দেশ বন্ধ।",
    "status_spotting": "%1$s এর জন্য অপেক্ষা।",
    "status_capturing": "একটি নির্দেশ শোনা হচ্ছে।",
    "wake_ack": "বলুন?",
    "confirm_started_already": "বাধা সতর্কতা আগে থেকেই চালু।",
    "confirm_stopped_already": "বাধা সতর্কতা আগে থেকেই বন্ধ।",
    "confirm_repeat": "আবার বলছি।",
    "confirm_nothing_to_repeat": "আবার বলার মতো সতর্কতা নেই।",
    "confirm_louder": "আওয়াজ বাড়ানো হয়েছে।",
    "confirm_quieter": "আওয়াজ কমানো হয়েছে।",
    "confirm_louder_max": "আওয়াজ আগে থেকেই সবচেয়ে জোরে।",
    "confirm_quieter_min": "আওয়াজ আগে থেকেই সবচেয়ে আস্তে।",
    "confirm_faster": "কথা দ্রুত করা হয়েছে।",
    "confirm_slower": "কথা ধীর করা হয়েছে।",
    "confirm_faster_max": "কথা আগে থেকেই সবচেয়ে দ্রুত।",
    "confirm_slower_min": "কথা আগে থেকেই সবচেয়ে ধীর।",
    "confirm_language": "ভাষা বদলানো হয়েছে।",
    "confirm_unknown": "এটা নির্দেশ ছিল না। তালিকার জন্য help বলুন।",
    "ahead_clear": "এখন সামনে কিছু দেখা যাচ্ছে না।",
    "help_commands": "%1$s এর পরে একটি ইংরেজি নির্দেশ বলুন: start, stop, pause, what is in front of me, repeat, louder, quieter, faster, slower, change language, অথবা help। ফোন জোরে নাড়ুন, TalkBack বন্ধ থাকলে পর্দায় দুবার চাপুন, অথবা এই পর্দা খোলা থাকলে ভলিউম চাবি চেপে ধরুন।",
    "cmd_start": "শুরু|start|start guide|begin",
    "cmd_stop": "থামো|stop|pause|stop guide",
    "cmd_ahead": "সামনে কী|what is in front of me|what is ahead|what is in front",
    "cmd_repeat": "আবার বলো|repeat|say again|repeat that",
    "cmd_louder": "জোরে|louder|speak louder|volume up",
    "cmd_quieter": "আস্তে|quieter|speak quieter|softer|volume down",
    "cmd_faster": "দ্রুত বলো|faster|speak faster",
    "cmd_slower": "ধীরে বলো|slower|speak slower",
    "cmd_language": "ভাষা বদলাও|change language|next language",
    "cmd_help": "সাহায্য|help|list commands|what can i say",
    "voice_off_action": "কণ্ঠ বন্ধ করুন",
    "voice_ear_note": "শোনার মডেল ইংরেজি। Hey Guide বলুন, তারপর ইংরেজি নির্দেশ। কণ্ঠস্বর ফোনেই থাকে।",
    "about_button": "পরিচিতি ও লাইসেন্স",
    "cd_about": "কথা ও ছবির লাইসেন্স খুলুন",
    "about_title": "পরিচিতি",
    "about_intro": "বাধা গাইড সাদা ছড়ির সঙ্গী, তার বদলি নয়।",
    "action_back": "পেছনে",
    "cd_back": "মূল পর্দায় ফিরুন",
}

SW = {
    "wake_on": "Amri za sauti: zimewashwa",
    "wake_off": "Amri za sauti: zimezimwa",
    "cd_wake_turn_on": "Amri za sauti zimezimwa. Gusa mara mbili kuwasha.",
    "cd_wake_turn_off": "Amri za sauti zimewashwa. Gusa mara mbili kuzima.",
    "wake_phrase_label": "Maneno ya kuamsha",
    "cd_wake_phrase": "Maneno ya kuamsha. Yaseme kabla ya amri. Chaguo-msingi ni Hey Guide.",
    "confirm_wake_phrase": "Maneno ya kuamsha sasa ni %1$s.",
    "wake_phrase_rejected": "Maneno hayo hayako kwenye modeli ya Kiingereza bila mtandao. Maneno ya kuamsha hayajabadilika.",
    "prompt_allow_microphone": "Simu itaomba kutumia maikrofoni. Tafadhali ruhusu. Obstacle Guide husikiliza kwenye simu maneno ya kuamsha, kisha amri moja. Sauti haitumwi kwenye mtandao. Unaweza kuzima hii kwenye mipangilio.",
    "status_mic_needed": "Maikrofoni imezimwa. Washa amri za sauti na uruhusu maikrofoni.",
    "status_mic_denied": "Ruhusa ya maikrofoni haikutolewa. Amri za sauti zinahitaji maikrofoni.",
    "status_mic_blocked": "Ruhusa ya maikrofoni imezuiwa. Fungua mipangilio, kisha Ruhusa, na uwashe maikrofoni.",
    "status_mic_unavailable": "Modeli ya sauti bila mtandao haikufunguka. Funga Obstacle Guide kisha uifungue tena.",
    "status_mic_busy": "Maikrofoni inatumiwa na programu nyingine. Ifunge, kisha washa amri za sauti tena.",
    "status_voice_off": "Amri za sauti zimezimwa.",
    "status_spotting": "Inasubiri %1$s.",
    "status_capturing": "Inasikiliza amri moja.",
    "wake_ack": "Sema?",
    "confirm_started_already": "Onyo la vizuizi tayari limewashwa.",
    "confirm_stopped_already": "Onyo la vizuizi tayari limezimwa.",
    "confirm_repeat": "Narudia.",
    "confirm_nothing_to_repeat": "Hakuna onyo la kurudia bado.",
    "confirm_louder": "Sauti imepandishwa.",
    "confirm_quieter": "Sauti imeshushwa.",
    "confirm_louder_max": "Sauti tayari iko juu kabisa.",
    "confirm_quieter_min": "Sauti tayari iko chini kabisa.",
    "confirm_faster": "Usemi umeharakishwa.",
    "confirm_slower": "Usemi umepunguzwa kasi.",
    "confirm_faster_max": "Usemi tayari uko wa haraka kabisa.",
    "confirm_slower_min": "Usemi tayari uko wa polepole kabisa.",
    "confirm_language": "Lugha imebadilishwa.",
    "confirm_unknown": "Hiyo si amri. Sema help kusikia orodha.",
    "ahead_clear": "Hakuna kinachoonekana mbele yako sasa.",
    "help_commands": "Baada ya %1$s, sema amri moja ya Kiingereza: start, stop, pause, what is in front of me, repeat, louder, quieter, faster, slower, change language, au help. Tikisa simu kwa nguvu, gusa skrini mara mbili TalkBack ikiwa imezimwa, au shikilia kitufe cha sauti skrini hii ikiwa wazi.",
    "cmd_start": "anza|start|start guide|begin",
    "cmd_stop": "simama|stop|pause|stop guide",
    "cmd_ahead": "nini mbele yangu|what is in front of me|what is ahead|what is in front",
    "cmd_repeat": "rudia|repeat|say again|repeat that",
    "cmd_louder": "ongeza sauti|louder|speak louder|volume up",
    "cmd_quieter": "punguza sauti|quieter|speak quieter|softer|volume down",
    "cmd_faster": "sema haraka|faster|speak faster",
    "cmd_slower": "sema polepole|slower|speak slower",
    "cmd_language": "badilisha lugha|change language|next language",
    "cmd_help": "msaada|help|list commands|what can i say",
    "voice_off_action": "Zima sauti",
    "voice_ear_note": "Modeli ya kusikiliza ni Kiingereza. Sema Hey Guide, kisha amri ya Kiingereza. Sauti inabaki kwenye simu.",
    "about_button": "Kuhusu na leseni",
    "cd_about": "Fungua leseni za sauti na picha",
    "about_title": "Kuhusu",
    "about_intro": "Obstacle Guide ni nyongeza ya fimbo nyeupe. Si badala yake.",
    "action_back": "Rudi",
    "cd_back": "Rudi kwenye skrini kuu",
}

ES = {
    "wake_on": "Órdenes de voz: activadas",
    "wake_off": "Órdenes de voz: desactivadas",
    "cd_wake_turn_on": "Las órdenes de voz están desactivadas. Toque dos veces para activarlas.",
    "cd_wake_turn_off": "Las órdenes de voz están activadas. Toque dos veces para desactivarlas.",
    "wake_phrase_label": "Frase de activación",
    "cd_wake_phrase": "Frase de activación. Dígalo antes de una orden. La frase inicial es Hey Guide.",
    "confirm_wake_phrase": "La frase de activación ahora es %1$s.",
    "wake_phrase_rejected": "Esas palabras no están en el modelo de inglés sin internet. La frase no cambió.",
    "prompt_allow_microphone": "El teléfono pedirá usar el micrófono. Permítalo. Obstacle Guide escucha en el teléfono la frase de activación y después una orden. La voz no se envía a internet. Puede apagarlo en los ajustes.",
    "status_mic_needed": "El micrófono está apagado. Active las órdenes de voz y permita el micrófono.",
    "status_mic_denied": "No se permitió el micrófono. Las órdenes de voz lo necesitan.",
    "status_mic_blocked": "El permiso del micrófono está bloqueado. Abra ajustes, luego Permisos, y active el micrófono.",
    "status_mic_unavailable": "No se pudo abrir el modelo de voz sin internet. Cierre Obstacle Guide y ábralo de nuevo.",
    "status_mic_busy": "Otra aplicación está usando el micrófono. Ciérrela y vuelva a activar las órdenes de voz.",
    "status_voice_off": "Las órdenes de voz están desactivadas.",
    "status_spotting": "Esperando %1$s.",
    "status_capturing": "Escuchando una orden.",
    "wake_ack": "¿Sí?",
    "confirm_started_already": "Los avisos de obstáculos ya están activados.",
    "confirm_stopped_already": "Los avisos de obstáculos ya están detenidos.",
    "confirm_repeat": "Repitiendo.",
    "confirm_nothing_to_repeat": "Todavía no hay un aviso para repetir.",
    "confirm_louder": "Más alto.",
    "confirm_quieter": "Más bajo.",
    "confirm_louder_max": "La voz ya está en el volumen más alto.",
    "confirm_quieter_min": "La voz ya está en el volumen más bajo.",
    "confirm_faster": "Más rápido.",
    "confirm_slower": "Más lento.",
    "confirm_faster_max": "La voz ya está en la velocidad más rápida.",
    "confirm_slower_min": "La voz ya está en la velocidad más lenta.",
    "confirm_language": "Idioma cambiado.",
    "confirm_unknown": "Eso no fue una orden. Diga help para oír la lista.",
    "ahead_clear": "Ahora no hay nada delante de usted.",
    "help_commands": "Después de %1$s, diga una orden en inglés: start, stop, pause, what is in front of me, repeat, louder, quieter, faster, slower, change language, o help. También puede sacudir el teléfono con fuerza, tocar dos veces la pantalla si TalkBack está apagado, o mantener pulsada una tecla de volumen con esta pantalla abierta.",
    "cmd_start": "empezar|iniciar|start|start guide|begin",
    "cmd_stop": "parar|pausa|stop|pause|stop guide",
    "cmd_ahead": "qué hay delante de mí|what is in front of me|what is ahead|what is in front",
    "cmd_repeat": "repetir|repeat|say again|repeat that",
    "cmd_louder": "más alto|louder|speak louder|volume up",
    "cmd_quieter": "más bajo|quieter|speak quieter|softer|volume down",
    "cmd_faster": "más rápido|faster|speak faster",
    "cmd_slower": "más lento|slower|speak slower",
    "cmd_language": "cambiar idioma|change language|next language",
    "cmd_help": "ayuda|help|list commands|what can i say",
    "voice_off_action": "Apagar la voz",
    "voice_ear_note": "El modelo que escucha está en inglés. Diga Hey Guide y después una orden en inglés. La voz se queda en el teléfono.",
    "about_button": "Acerca de y licencias",
    "cd_about": "Abrir las licencias de la voz y de las fotos",
    "about_title": "Acerca de",
    "about_intro": "Obstacle Guide es un complemento del bastón blanco. No lo sustituye.",
    "action_back": "Volver",
    "cd_back": "Volver a la pantalla principal",
}

LOCALES = {
    "values": EN,
    "values-hi": HI,
    "values-ta": TA,
    "values-bn": BN,
    "values-sw": SW,
    "values-es": ES,
}


def esc(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace("'", "\\'")


def main() -> None:
    for folder, strings in LOCALES.items():
        if folder != "values":
            strings = {**strings, "about_body": ABOUT, "wake_phrase_hint": "Hey Guide"}
        path = ROOT / folder / "strings.xml"
        text = path.read_text()
        missing = []
        block = []
        for name, value in strings.items():
            token = f'name="{name}"'
            if token in text:
                continue
            block.append(f'    <string name="{name}">{esc(value)}</string>')
            missing.append(name)
        if not block:
            print(folder, "already complete")
            continue
        if "</resources>" not in text:
            raise SystemExit(f"no resources end in {path}")
        text = text.replace("</resources>", "\n".join(block) + "\n</resources>\n")
        path.write_text(text)
        print(folder, "added", len(missing))


if __name__ == "__main__":
    main()
