// Fades out the Codio boot screen that index.html shows while the app loads.
// It stays up for at least MIN_VISIBLE ms after page load so it never just flickers.

const MIN_VISIBLE = 1200;

const hideBootScreen = () => {
    const element = document.getElementById('codio-boot');
    if (!element) return;
    const wait = Math.max(0, MIN_VISIBLE - performance.now());
    setTimeout(() => {
        element.classList.add('codio-boot-hidden');
        setTimeout(() => element.remove(), 600);
    }, wait);
};

export default hideBootScreen;
