// Which sources have audio in the viewer's language, from the release name and the addon's text.
// Sources that only name another language ("KOREAN", 🇰🇷) are tried after all others, so a Korean-only release isn't
// picked when an English, dubbed or dual-audio one exists. Releases that don't say are assumed to be fine.
// Same rules as __codioLangRank in the Android and iPhone apps.

const LANGS: Record<string, string[]> = {
    eng: ['english', 'eng', '🇬🇧', '🇺🇸'],
    spa: ['spanish', 'spa', 'esp', 'español', 'espanol', 'castellano', 'latino', '🇪🇸', '🇲🇽'],
    fre: ['french', 'fre', 'fra', 'français', 'francais', 'truefrench', 'vff', 'vfq', 'vf2', '🇫🇷'],
    ger: ['german', 'ger', 'deu', 'deutsch', '🇩🇪'],
    ita: ['italian', 'ita', 'italiano', '🇮🇹'],
    por: ['portuguese', 'por', 'português', 'portugues', '🇵🇹', '🇧🇷'],
    nld: ['dutch', 'nld', 'dut', 'nederlands', 'flemish', '🇳🇱'],
    pol: ['polish', 'pol', 'polski', '🇵🇱'],
    rus: ['russian', 'rus', 'русский', '🇷🇺'],
    tur: ['turkish', 'tur', 'türkçe', 'turkce', '🇹🇷'],
    ara: ['arabic', 'ara', '🇸🇦', '🇦🇪', '🇪🇬'],
    hin: ['hindi', 'hin', '🇮🇳'],
    jpn: ['japanese', 'jpn', 'jap', '🇯🇵'],
    kor: ['korean', 'kor', '🇰🇷'],
    zho: ['chinese', 'zho', 'chi', 'chs', 'cht', 'mandarin', 'cantonese', '🇨🇳', '🇹🇼', '🇭🇰'],
};

const ALIAS: Record<string, string> = {
    en: 'eng', es: 'spa', fr: 'fre', fra: 'fre', de: 'ger', deu: 'ger', it: 'ita', pt: 'por', nl: 'nld', dut: 'nld',
    pl: 'pol', ru: 'rus', tr: 'tur', ar: 'ara', hi: 'hin', ja: 'jpn', ko: 'kor', zh: 'zho', chi: 'zho',
};

const preferredCode = (language?: string | null) => {
    const code = String(language || 'eng').toLowerCase();
    return LANGS[code] ? code : (ALIAS[code] ?? 'eng');
};

const mentions = (text: string, words: string[]) => words.some((word) => /^[a-z]+$/.test(word) ?
    new RegExp(`(^|[^a-z])${word}([^a-z]|$)`).test(text)
    :
    text.includes(word));

const MULTI = /(^|[^a-z])(multi(?![ .-]?subs?)|multi[ .-]?audio|dual([ .-]?audio)?|dubbed|dub)([^a-z]|$)/;

// 0: fine (the viewer's language, dubbed / multi-audio, or not said); 1: audio only in another language.
export const languageRank = (text: string, audioLanguage?: string | null): number => {
    const preferred = preferredCode(audioLanguage);
    // Subtitle languages say nothing about the audio: "ENG subs", "Subs: English", "ESub".
    const t = String(text || '').toLowerCase().replace(/_/g, ' ')
        .replace(/[a-z]+[ .-]*(hard[ .-]?)?(sub|subs|subbed|subtitles?)(?![a-z])/g, ' ')
        .replace(/(sub|subs|subtitles?)[ .:-]*[a-z]+/g, ' ')
        .replace(/(^|[^a-z])e?subs?([^a-z]|$)/g, ' ');
    if (mentions(t, LANGS[preferred])) return 0;
    let other = false;
    for (const [code, words] of Object.entries(LANGS)) {
        if (code === preferred || !mentions(t, words)) continue;
        other = true;
        // "Hindi Dubbed", "ITA dub": dubbed into that other language.
        const dubs = t.replace(/[ .-]*dub(bed)?(?![a-z])/g, '\u0001');
        if (mentions(dubs, words.map((word) => `${word}\u0001`))) return 1;
    }
    if (!other) return 0;
    // Another language named, but a dubbed, dual or multi-audio release usually has the viewer's too.
    return MULTI.test(t) ? 0 : 1;
};
