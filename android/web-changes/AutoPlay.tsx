// Settings section for the AutoPlay fork: pick a quality, auto-play it, and
// jump to the next source when one doesn't work.

import React, { forwardRef, useCallback, useEffect, useMemo, useState } from 'react';
import { MultiselectMenu, TextInput, Toggle } from 'stremio/components';
import { useAutoPlaySettings, DEFAULT_SETTINGS, QUALITIES, type FallbackMode, type Quality } from 'stremio/common/AutoPlay';
import { Category, Option, Section } from '../components';
import styles from './AutoPlay.less';

type Props = {
    profile: Profile,
};

const QUALITY_LABELS: Record<string, string> = {
    '2160p': '4K (2160p)',
    '1440p': '1440p',
    '1080p': 'Full HD (1080p)',
    '720p': 'HD (720p)',
    '480p': 'SD (480p)',
};

const FALLBACK_OPTIONS: { value: FallbackMode, label: string }[] = [
    { value: 'closest', label: 'Closest quality (lower first, then higher)' },
    { value: 'lower', label: 'Only same or lower quality' },
    { value: 'exact', label: 'Only the exact quality' },
];

const secondsOptions = (values: number[], zeroLabel?: string) => values.map((value) => ({
    value: `${value}`,
    label: value === 0 && zeroLabel ? zeroLabel : `${value}s`,
}));

const AutoPlay = forwardRef<HTMLDivElement, Props>(({ profile }: Props, ref) => {
    const [settings, update] = useAutoPlaySettings();
    const externalPlayer = profile.settings.playerType !== null;

    const [keywords, setKeywords] = useState(settings.excludeKeywords);
    useEffect(() => setKeywords(settings.excludeKeywords), [settings.excludeKeywords]);
    const commitKeywords = useCallback(() => {
        if (keywords !== settings.excludeKeywords) update({ excludeKeywords: keywords });
    }, [keywords, settings.excludeKeywords, update]);

    const qualitySelect = useMemo(() => ({
        options: QUALITIES.map((quality) => ({ value: quality, label: QUALITY_LABELS[quality] })),
        value: settings.preferredQuality,
        onSelect: (value: string) => update({ preferredQuality: value as Quality }),
    }), [settings.preferredQuality, update]);

    const fallbackSelect = useMemo(() => ({
        options: FALLBACK_OPTIONS,
        value: settings.fallbackMode,
        onSelect: (value: string) => update({ fallbackMode: value as FallbackMode }),
    }), [settings.fallbackMode, update]);

    const countdownSelect = useMemo(() => ({
        options: secondsOptions([0, 2, 3, 5, 10], 'Start immediately'),
        value: `${settings.countdownSeconds}`,
        onSelect: (value: string) => update({ countdownSeconds: parseInt(value, 10) }),
    }), [settings.countdownSeconds, update]);

    const addonWaitSelect = useMemo(() => ({
        options: secondsOptions([0, 3, 5, 8, 15, 30], 'Do not wait'),
        value: `${settings.addonWaitSeconds}`,
        onSelect: (value: string) => update({ addonWaitSeconds: parseInt(value, 10) }),
    }), [settings.addonWaitSeconds, update]);

    const startupTimeoutSelect = useMemo(() => ({
        options: secondsOptions([0, 15, 20, 30, 45, 60, 90], 'Never'),
        value: `${settings.startupTimeoutSeconds}`,
        onSelect: (value: string) => update({ startupTimeoutSeconds: parseInt(value, 10) }),
    }), [settings.startupTimeoutSeconds, update]);

    const bitrateSelect = useMemo(() => ({
        options: [0, 25, 40, 60, 70, 80, 100, 150].map((value) => ({
            value: `${value}`,
            label: value === 0 ? 'No limit' : `${value} Mbit/s`,
        })),
        value: `${settings.maxBitrateMbps ?? 0}`,
        onSelect: (value: string) => update({ maxBitrateMbps: parseInt(value, 10) }),
    }), [settings.maxBitrateMbps, update]);

    const maxAttemptsSelect = useMemo(() => ({
        options: [3, 5, 8, 10, 15, 25].map((value) => ({ value: `${value}`, label: `${value}` })),
        value: `${settings.maxAttempts}`,
        onSelect: (value: string) => update({ maxAttempts: parseInt(value, 10) }),
    }), [settings.maxAttempts, update]);

    const toggle = (key: 'enabled' | 'hideSources' | 'skipOnError' | 'verifyResolution' | 'preferCached') => ({
        checked: settings[key],
        onClick: () => update({ [key]: !settings[key] }),
    });

    return (
        <Section ref={ref} label={'Smart auto-play'}>
            <Category icon={'hdr'} label={'Quality'}>
                <Option label={'Auto-play preferred quality'}>
                    <Toggle tabIndex={-1} {...toggle('enabled')} />
                </Option>
                {
                    externalPlayer ?
                        <div className={styles['note']}>
                            Smart auto-play only works with the built-in player. Set the external player option to off under Player to use it.
                        </div>
                        :
                        null
                }
                <Option label={'Hide sources (Play button instead of a list)'}>
                    <Toggle tabIndex={-1} disabled={!settings.enabled} {...toggle('hideSources')} />
                </Option>
                <Option label={'Preferred quality'}>
                    <MultiselectMenu className={'multiselect'} disabled={!settings.enabled} {...qualitySelect} />
                </Option>
                <Option label={'If not available'}>
                    <MultiselectMenu className={'multiselect'} disabled={!settings.enabled} {...fallbackSelect} />
                </Option>
                <Option label={'Skip sources above this bitrate (for slower connections)'}>
                    <MultiselectMenu className={'multiselect'} disabled={!settings.enabled} {...bitrateSelect} />
                </Option>
                <Option label={'Prefer cached or instant sources'}>
                    <Toggle tabIndex={-1} disabled={!settings.enabled} {...toggle('preferCached')} />
                </Option>
                <Option label={'Never auto-pick sources containing'}>
                    <TextInput
                        className={styles['text-input']}
                        disabled={!settings.enabled}
                        value={keywords}
                        placeholder={DEFAULT_SETTINGS.excludeKeywords}
                        onChange={(event) => setKeywords(event.currentTarget.value)}
                        onBlur={commitKeywords}
                        onSubmit={commitKeywords}
                    />
                </Option>
                <Option label={'Countdown before playing'}>
                    <MultiselectMenu className={'multiselect'} disabled={!settings.enabled} {...countdownSelect} />
                </Option>
                <Option label={'Wait for slow addons up to'}>
                    <MultiselectMenu className={'multiselect'} disabled={!settings.enabled} {...addonWaitSelect} />
                </Option>
            </Category>
            <Category icon={'next'} label={'Jump to next source'}>
                <Option label={'When a source fails to play'}>
                    <Toggle tabIndex={-1} disabled={!settings.enabled} {...toggle('skipOnError')} />
                </Option>
                <Option label={'When a source does not start within'}>
                    <MultiselectMenu className={'multiselect'} disabled={!settings.enabled} {...startupTimeoutSelect} />
                </Option>
                <Option label={'When the real resolution is lower than promised'}>
                    <Toggle tabIndex={-1} disabled={!settings.enabled} {...toggle('verifyResolution')} />
                </Option>
                <Option label={'Maximum sources to try'}>
                    <MultiselectMenu className={'multiselect'} disabled={!settings.enabled} {...maxAttemptsSelect} />
                </Option>
            </Category>
        </Section>
    );
});

AutoPlay.displayName = 'AutoPlay';

export default AutoPlay;
