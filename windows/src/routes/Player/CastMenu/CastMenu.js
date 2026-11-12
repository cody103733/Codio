// The actors in the title being played, with their photos, from TMDB.

const React = require('react');
const PropTypes = require('prop-types');
const classnames = require('classnames');
const styles = require('./styles');

const API = 'https://api.themoviedb.org/3/';
// The TMDB key comes from the build (CODIO_TMDB_KEY) or from the user's own key in local storage.
const getKey = () => process.env.CODIO_TMDB_KEY || (typeof localStorage !== 'undefined' && localStorage.getItem('codio.tmdbKey')) || '';
const PHOTOS = 'https://image.tmdb.org/t/p/w185';
const MAX_ACTORS = 40;
const cache = new Map();

const getJson = async (path) => {
    const KEY = getKey();
    if (!KEY) {
        throw new Error('No TMDB key');
    }
    const response = await fetch(`${API}${path}${path.includes('?') ? '&' : '?'}api_key=${KEY}`);
    if (!response.ok) {
        throw new Error(`TMDB answered ${response.status}`);
    }
    return response.json();
};

const fetchActors = async (imdbId, isMovie) => {
    const found = await getJson(`find/${imdbId}?external_source=imdb_id`);
    const movie = found.movie_results?.[0];
    const show = found.tv_results?.[0];
    const useMovie = isMovie ? Boolean(movie) : !show && Boolean(movie);
    const hit = useMovie ? movie : show;
    if (!hit) {
        return [];
    }
    const credits = await getJson(useMovie ? `movie/${hit.id}/credits` : `tv/${hit.id}/aggregate_credits`);
    return (credits.cast ?? [])
        .filter((actor) => actor.name)
        .slice(0, MAX_ACTORS)
        .map((actor) => ({
            name: actor.name,
            role: actor.roles?.[0]?.character || actor.character || null,
            photo: actor.profile_path ? `${PHOTOS}${actor.profile_path}` : null,
        }));
};

const namesFromAddon = (metaItem) => (metaItem?.links ?? [])
    .filter(({ category, name }) => category === 'Cast' && name)
    .map(({ name }) => ({ name, role: null, photo: null }));

const imdbIdOf = (metaItem, videoId) => {
    return [metaItem?.id, typeof videoId === 'string' ? videoId.split(':')[0] : null]
        .find((id) => typeof id === 'string' && /^tt\d+$/.test(id)) ?? null;
};

const Actor = ({ actor }) => {
    const [photoFailed, setPhotoFailed] = React.useState(false);
    return (
        <div className={styles['actor']}>
            {
                actor.photo && !photoFailed ?
                    <img className={styles['photo']} src={actor.photo} alt={''} loading={'lazy'} onError={() => setPhotoFailed(true)} />
                    :
                    <div className={styles['photo']}>{actor.name.charAt(0)}</div>
            }
            <div className={styles['details']}>
                <div className={styles['name']}>{actor.name}</div>
                {actor.role ? <div className={styles['role']}>{actor.role}</div> : null}
            </div>
        </div>
    );
};

Actor.propTypes = {
    actor: PropTypes.object.isRequired,
};

const CastMenu = React.memo(React.forwardRef(({ className, metaItem, videoId }, ref) => {
    const imdbId = imdbIdOf(metaItem, videoId);
    const isMovie = metaItem?.type === 'movie';
    const [actors, setActors] = React.useState(() => cache.get(imdbId) ?? null);
    const fallback = React.useMemo(() => namesFromAddon(metaItem), [metaItem]);
    const onMouseDown = React.useCallback((event) => {
        event.nativeEvent.castMenuClosePrevented = true;
    }, []);
    React.useEffect(() => {
        if (cache.has(imdbId)) {
            setActors(cache.get(imdbId));
            return;
        }
        if (imdbId === null) {
            setActors(fallback);
            return;
        }
        let current = true;
        setActors(null);
        fetchActors(imdbId, isMovie)
            .then((found) => {
                if (found.length > 0) {
                    cache.set(imdbId, found);
                }
                if (current) {
                    setActors(found.length > 0 ? found : fallback);
                }
            })
            .catch((error) => {
                console.warn('Cast lookup failed', error.message);
                if (current) {
                    setActors(fallback);
                }
            });
        return () => {
            current = false;
        };
    }, [imdbId, isMovie, fallback]);
    return (
        <div ref={ref} className={classnames(className, styles['cast-menu-container'])} onMouseDown={onMouseDown}>
            <div className={styles['title']}>Cast</div>
            <div className={styles['list']}>
                {
                    actors === null ?
                        <div className={styles['message']}>Loading…</div>
                        :
                        actors.length === 0 ?
                            <div className={styles['message']}>No cast information for this title.</div>
                            :
                            actors.map((actor, index) => <Actor key={`${index}:${actor.name}`} actor={actor} />)
                }
            </div>
            <div className={styles['credit']}>This product uses the TMDB API but is not endorsed or certified by TMDB.</div>
        </div>
    );
}));

CastMenu.displayName = 'CastMenu';

CastMenu.propTypes = {
    className: PropTypes.string,
    metaItem: PropTypes.object,
    videoId: PropTypes.string,
};

module.exports = CastMenu;