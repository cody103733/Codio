// Codio start screen when nobody is signed in: sign in, or go on without an account.

import React from 'react';
import classnames from 'classnames';
import { useNavigate } from 'react-router';
import BrandLogo from 'stremio/components/BrandLogo';
import { styles } from './components';
import SignIn from './SignIn';

const Intro = () => {
    const navigate = useNavigate();
    return (
        <div className={classnames(styles['codio'], styles['flat'])}>
            <div className={styles['intro']}>
                <BrandLogo className={styles['intro-logo']} />
                <div className={styles['intro-title']}>Sign in to Codio</div>
                <SignIn onDone={() => navigate('/', { replace: true })} cancelLabel={'Continue without an account'} />
            </div>
        </div>
    );
};

export default Intro;
