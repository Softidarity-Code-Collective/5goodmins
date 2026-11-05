-- V100__seed_demo_data.sql — the demo content.
--
-- **This runs in every environment, including the deployed site**, which is
-- the decision in ARCHITECTURE.md §4 and the reason this is an ordinary
-- versioned migration rather than anything cleverer: the deployed site is the
-- portfolio, and an aggregator with no ingestion pipeline and no onboarded
-- NGOs is an empty page until something puts content in it. ADR-021 records
-- the two sub-decisions §4 did not cover — who these accounts are, and why
-- the test suite is the one place that does NOT load this file.
--
-- Numbered 100 so the schema keeps V1–V20. It lives in db/seed rather than
-- db/migration so that one line of test configuration can leave it out;
-- `spring.flyway.locations` in application.yaml names both directories, so
-- development and production are identical and there is no profile involved.
--
-- Versioned, so it is applied once and then frozen like any other migration:
-- **editing this file after it has run is a checksum failure, not an edit.**
-- Locally, re-seed with `docker compose down -v` and start again; once it has
-- run on Railway, more content is a V101.
--
-- Two rules on the content, from §4. Seeded authors use emails at
-- `seed.example` — a reserved domain (RFC 2606) that can never receive mail,
-- so one DELETE plus the ON DELETE CASCADE removes every demo row the day
-- real NGOs arrive. And every date is relative to now(), because fixed
-- literals mean the whole catalogue is expired and invisible on the day you
-- demo it.
--
-- Relative, but evaluated ONCE — when Flyway applies this file. From that
-- moment the end dates are fixed timestamps counting down, which is why the
-- ends_in_days column below is mostly 180 to 360 rather than the 12 to 150 it
-- started as: 27 of these stay live for at least six months after the deploy.
-- One is expired on purpose and three end inside 60 days, so the "ends soon"
-- and "expired" states are designable on day one. Refreshing the catalogue is
-- a V101 and ADR-021 says when it comes due.
--
-- The content deliberately includes the awkward cases rather than 31
-- comfortable ones: an action with no external link, an expired one, one of
-- each non-published status, three regions on one action, and a title at the
-- 200-character limit.


-- ---------------------------------------------------------------------------
-- 1. The two demo authors
-- ---------------------------------------------------------------------------
--
-- **Nobody can log in as either of them, and that is the point.** This file
-- runs on the live site, so a password published in this repository would be
-- a published password for a real account on a real deployment. Each hash
-- below is BCrypt over 32 bytes from SecureRandom that were never written
-- down: syntactically a hash, verifiable against nothing. There is no
-- password-reset flow to recover them with either (that is v1.1), so these
-- rows are content, permanently.
--
-- To get an account for yourself in development, register at /register and
-- click the link Mailpit catches on :8025 — that is the real flow, it takes
-- twenty seconds, and it is also how you test it. The README has the one line
-- of SQL that promotes your own row to ADMIN.
--
-- email_verified_at is set anyway, for two reasons that are not about login:
-- the nightly purge deletes accounts that never confirmed (ADR-002), and it
-- would otherwise take the demo content with them after seven days.

INSERT INTO app_user (email, password_hash, display_name, bio, website_url,
                      role, email_verified_at, poster_verified_at, locale, created_at)
VALUES
    ('poster@seed.example',
     '{bcrypt}$2a$10$dobZUZ/Tizsicz/wPiaDmu.AAl4B.7x3x8cXBvHWD9dnA3eW6lHza',
     'Зелена Варна', 'Гражданско сдружение за опазване на Черноморското крайбрежие. Работим от 2014 г.',
     'https://example.org/zelena-varna',
     'POSTER', now() - interval '60 days', now() - interval '45 days', 'bg', now() - interval '60 days'),

    ('user@seed.example',
     '{bcrypt}$2a$10$eM4mKnp1W9YQLScc10OzkewSc3ZsITMyC5jDaJgzc0X6/1W6coJJK',
     'Мария Петрова', 'Учител по биология. Интересувам се от екология и права на животните.', NULL,
     'USER', now() - interval '30 days', NULL, 'bg', now() - interval '30 days');


-- ---------------------------------------------------------------------------
-- 2. The actions
-- ---------------------------------------------------------------------------
--
-- One VALUES list driving a loop, rather than 31 INSERT statements followed
-- by 70 more for the join tables: the categories and regions of an action are
-- written next to it, where they can be read as one row.
--
-- normalized_url is computed here by an expression that APPROXIMATES
-- ADR-011's rule (lowercase, no scheme, no www., no trailing slash). The real
-- normalization lives in the application, is more thorough, and is what runs
-- for user-submitted actions; this only has to be deterministic and unique,
-- because uq_action_normalized_url is a real constraint even on seed rows.

DO $$
DECLARE
    seeded      record;
    new_action  bigint;
BEGIN
    FOR seeded IN
        SELECT * FROM (VALUES
            -- title, description, type, external_url, video_url,
            -- categories, regions, status, published_days_ago, ends_in_days, author
            ('Спрете застрояването на дюните при Карадере',
             'Инвестиционен проект предвижда хотелски комплекс на един от последните незастроени плажове по Черноморието. Подписката ще бъде внесена в Министерството на околната среда и водите.',
             'PETITION', 'https://www.peticiya.example.org/karadere', NULL,
             ARRAY['NATURE'], ARRAY['VARNA'], 'PUBLISHED', 1, 210, 'poster'),

            ('Безопасни пешеходни пътеки пред училищата в Пловдив',
             'Три деца са пострадали на нерегулирани пътеки през последната година. Писмото иска изкуствени неравности и осветление пред всяко училище в района.',
             'LETTER_OR_EMAIL', 'https://example.org/pisma/plovdiv-uchilishta', NULL,
             ARRAY['CHILDREN', 'LOCAL_COMMUNITIES'], ARRAY['PLOVDIV'], 'PUBLISHED', 2, 240, 'user'),

            ('Анкета: достъпен ли е градският транспорт за хора с увреждания',
             'Събираме опит от хора с двигателни и зрителни увреждания за рампите, звуковите съобщения и спирките. Резултатите отиват при общинския съвет.',
             'SURVEY', 'https://example.org/anketa/transport-dostupnost', NULL,
             ARRAY['DISABILITY', 'LOCAL_COMMUNITIES'], ARRAY['SOFIA_CITY'], 'PUBLISHED', 3, 195, 'poster'),

            ('Детска млечна кухня в Кюстендил',
             'Общината закри кухнята през 2019 г. Над 200 семейства искат връщането ѝ.',
             'PETITION', 'https://www.peticiya.example.org/kyustendil-kuhnya', NULL,
             ARRAY['CHILDREN', 'SOCIAL'], ARRAY['KYUSTENDIL'], 'PUBLISHED', 4, 300, 'user'),

            ('Против изсичането в Природен парк Витоша',
             'Планът за управление допуска санитарна сеч в зони с вековни гори. Подписката подкрепя възражението на природозащитните организации.',
             'PETITION', 'https://www.peticiya.example.org/vitosha-sech', NULL,
             ARRAY['NATURE'], ARRAY['SOFIA_CITY', 'SOFIA_PROVINCE'], 'PUBLISHED', 5, 185, 'poster'),

            ('Писмо до РИОСВ за мръсния въздух в Димитровград',
             'Нормите за фини прахови частици са превишени 48 пъти тази зима. Искаме публичен график на проверките в предприятията.',
             'LETTER_OR_EMAIL', 'https://example.org/pisma/dimitrovgrad-vazduh', NULL,
             ARRAY['HEALTHCARE', 'NATURE'], ARRAY['HASKOVO'], 'PUBLISHED', 6, 265, 'user'),

            ('Общински приют за бездомни кучета в Сливен',
             'Градът няма приют, а кастрационната програма спря преди две години. Петицията предлага конкретен терен и бюджет.',
             'PETITION', 'https://www.peticiya.example.org/sliven-priyut', NULL,
             ARRAY['ANIMALS'], ARRAY['SLIVEN'], 'PUBLISHED', 7, 320, 'user'),

            ('Анкета сред медицинските сестри за натоварването в спешните отделения',
             'Десет минути. Попълва се анонимно и служи за доклад до Министерството на здравеопазването.',
             'SURVEY', 'https://example.org/anketa/spesna-pomosht', NULL,
             ARRAY['HEALTHCARE', 'LABOUR_RIGHTS'], ARRAY['NATIONAL'], 'PUBLISHED', 8, 200, 'poster'),

            ('Ремонт на отсечката Враца - Мездра',
             'Участъкът е с дупки от три години и е единственият път до болницата за седем села.',
             'PETITION', 'https://www.peticiya.example.org/vratsa-mezdra', NULL,
             ARRAY['LOCAL_COMMUNITIES'], ARRAY['VRATSA'], 'PUBLISHED', 9, 280, 'user'),

            ('Писмо до МОН за ресурсните учители',
             'Един ресурсен учител се грижи за над 20 деца в някои области. Писмото иска норматив и финансиране.',
             'LETTER_OR_EMAIL', 'https://example.org/pisma/mon-resursni', NULL,
             ARRAY['CHILDREN', 'DISABILITY'], ARRAY['NATIONAL'], 'PUBLISHED', 10, 190, 'poster'),

            ('Против закриването на болницата в Девин',
             'Най-близкото спешно отделение след закриването ще е на 60 км по планински път.',
             'PETITION', 'https://www.peticiya.example.org/devin-bolnitsa', NULL,
             ARRAY['HEALTHCARE', 'LOCAL_COMMUNITIES'], ARRAY['SMOLYAN'], 'PUBLISHED', 11, 60, 'user'),

            ('Споделете кампанията за хоспис в Русе',
             'Хосписът събира средства за десет легла. Най-полезното сега е кампанията да стигне до повече хора.',
             'SOCIAL_MEDIA', 'https://example.org/kampaniya/ruse-hospis', NULL,
             ARRAY['HEALTHCARE', 'SOCIAL'], ARRAY['RUSE'], 'PUBLISHED', 12, 230, 'poster'),

            ('Достъпни детски площадки в Бургас',
             'Нито една от 42-те общински площадки няма съоръжение за дете в количка.',
             'PETITION', 'https://www.peticiya.example.org/burgas-ploshtadki', NULL,
             ARRAY['CHILDREN', 'DISABILITY'], ARRAY['BURGAS'], 'PUBLISHED', 13, 350, 'user'),

            ('Сигнал до Комисията за защита от дискриминация',
             'Образец на писмо за отказан достъп до заведение заради етнически произход. Попълва се за пет минути.',
             'LETTER_OR_EMAIL', 'https://example.org/pisma/kzd-signal', NULL,
             ARRAY['HUMAN_RIGHTS'], ARRAY['NATIONAL'], 'PUBLISHED', 14, 360, 'poster'),

            ('Анкета за условията на труд в шивашките цехове',
             'Анонимно проучване за работното време, извънредния труд и отпуските. Данните ще бъдат предоставени на синдикатите.',
             'SURVEY', 'https://example.org/anketa/shivashki-tsehove', NULL,
             ARRAY['LABOUR_RIGHTS'], ARRAY['BLAGOEVGRAD'], 'PUBLISHED', 15, 205, 'user'),

            ('Опазване на вековните дървета в центъра на Стара Загора',
             'Проектът за нова пешеходна зона предвижда премахването на 18 дървета на възраст над 80 години.',
             'PETITION', 'https://www.peticiya.example.org/stara-zagora-darveta', NULL,
             ARRAY['NATURE', 'LOCAL_COMMUNITIES'], ARRAY['STARA_ZAGORA'], 'PUBLISHED', 16, 245, 'poster'),

            ('По-строг контрол над незаконния улов в Дунав',
             'Бракониерският улов на есетрови риби продължава въпреки забраната. Искаме повече проверки в трите крайдунавски области.',
             'PETITION', 'https://www.peticiya.example.org/dunav-ulov', NULL,
             ARRAY['ANIMALS', 'NATURE'], ARRAY['VIDIN', 'RUSE', 'SILISTRA'], 'PUBLISHED', 17, 330, 'poster'),

            ('Улично осветление в квартал без улични лампи',
             'Три улици са без осветление от десет години. Писмото е до общината и до областния управител.',
             'LETTER_OR_EMAIL', 'https://example.org/pisma/osvetlenie-kvartal', NULL,
             ARRAY['LOCAL_COMMUNITIES', 'SOCIAL'], ARRAY['PLOVDIV'], 'PUBLISHED', 18, 215, 'user'),

            ('Анкета: как поскъпването на тока се отразява на малкия бизнес',
             'За занаятчии, пекарни и малки производства. Резултатите се публикуват обобщено.',
             'SURVEY', 'https://example.org/anketa/tok-biznes', NULL,
             ARRAY['SOCIAL', 'POLITICAL'], ARRAY['NATIONAL'], 'PUBLISHED', 19, 255, 'poster'),

            ('Защита на жертвите на домашно насилие',
             'Петиция за кризисни центрове във всяка областна управа и за спешни заповеди за защита в рамките на 24 часа.',
             'PETITION', 'https://www.peticiya.example.org/zashtita-nasilie', NULL,
             ARRAY['HUMAN_RIGHTS', 'SOCIAL'], ARRAY['NATIONAL'], 'PUBLISHED', 20, 340, 'poster'),

            -- No external link: the action IS the description. The detail page
            -- needs a variant with no call-to-action button (frontend brief,
            -- page 2), and this row is the one that proves it renders.
            ('Доброволци за почистване на река Марица',
             'Събираме се в събота в 10:00 при моста на бул. Македония. Носете ръкавици; чувалите са осигурени от общината. Няма регистрация и няма външна връзка - просто елате.',
             'OTHER', NULL, NULL,
             ARRAY['NATURE', 'LOCAL_COMMUNITIES'], ARRAY['PLOVDIV'], 'PUBLISHED', 21, 30, 'user'),

            ('Запазете автобусната линия до селата край Ловеч',
             'Линията обслужва пет села без друг транспорт. Превозвачът я прекратява от следващия месец.',
             'PETITION', 'https://www.peticiya.example.org/lovech-avtobus', NULL,
             ARRAY['LOCAL_COMMUNITIES', 'SOCIAL'], ARRAY['LOVECH'], 'PUBLISHED', 22, 45, 'user'),

            ('Писмо до евродепутатите за правата на сезонните работници',
             'Образец на писмо на български и английски за условията на наемане на сезонни работници в земеделието.',
             'LETTER_OR_EMAIL', 'https://example.org/pisma/sezonni-rabotnitsi', NULL,
             ARRAY['LABOUR_RIGHTS', 'HUMAN_RIGHTS'], ARRAY['INTERNATIONAL'], 'PUBLISHED', 23, 310, 'poster'),

            ('Анкета за качеството на водата в Перник',
             'Три години след кризата питаме домакинствата какво е състоянието на водата при тях.',
             'SURVEY', 'https://example.org/anketa/pernik-voda', NULL,
             ARRAY['HEALTHCARE', 'NATURE'], ARRAY['PERNIK'], 'PUBLISHED', 24, 225, 'user'),

            ('Кастрационна програма в Добрич',
             'Програмата спря през 2022 г. и популацията на бездомните кучета се удвои.',
             'PETITION', 'https://www.peticiya.example.org/dobrich-kastratsiya', NULL,
             ARRAY['ANIMALS'], ARRAY['DOBRICH'], 'PUBLISHED', 25, 270, 'user'),

            -- The video variant: the detail page embeds a whitelisted host.
            -- The id is a placeholder and resolves to nothing. Put a real
            -- Bulgarian video here before this is demoed to anyone -- and
            -- never a joke link, because this row is what ends up on screen.
            ('Какво се случва с боклука, който изхвърляме',
             'Десетминутен филм на доброволци за пътя на отпадъците от контейнера до депото и за това колко малко от тях се рециклират.',
             'OTHER', 'https://example.org/film/boklukat', 'https://www.youtube.com/watch?v=OtpadaciBG1',
             ARRAY['NATURE'], ARRAY['NATIONAL'], 'PUBLISHED', 26, 355, 'poster'),

            -- A title at the 200-character limit and a long description, so the
            -- card and the detail page are designed against the worst case
            -- rather than against a short one.
            ('Петиция за общинска програма за енергийна ефективност на многофамилните жилищни сгради в областните центрове, с конкретни срокове, публичен регистър на кандидатите и ясни и публични критерии за подбор',
             'Националната програма за саниране спря, а общините нямат собствени програми. Искаме всяка общинска администрация в областен център да приеме програма с конкретни срокове и бюджет, публичен регистър на подадените заявления и критерии за подбор, които са известни предварително. Текстът на петицията е изготвен с помощта на три неправителствени организации и е съгласуван с експерти по енергийна ефективност. Подписката ще бъде внесена в Националното сдружение на общините в Република България, а копие ще получи и парламентарната комисия по енергетика. Подписването отнема по-малко от минута и изисква само име и населено място.',
             'PETITION', 'https://www.peticiya.example.org/energiyna-efektivnost', NULL,
             ARRAY['SOCIAL', 'LOCAL_COMMUNITIES', 'POLITICAL'], ARRAY['NATIONAL'], 'PUBLISHED', 27, 290, 'poster'),

            -- Expired: past its end date, so it must not appear in any listing.
            -- Still PUBLISHED, because expiry is derived and never a status.
            ('Спасете старата поща в Шумен',
             'Сградата е паметник на културата и беше обявена за продажба. Кампанията приключи - сградата остава общинска.',
             'PETITION', 'https://www.peticiya.example.org/shumen-poshta', NULL,
             ARRAY['LOCAL_COMMUNITIES'], ARRAY['SHUMEN'], 'PUBLISHED', 80, -5, 'user'),

            -- The three non-public states, visible only on "my actions" and in
            -- the admin queues (frontend brief, pages 12, 16 and 17).
            ('Нощен транспорт във Велико Търново през уикенда',
             'Предложение за две нощни линии в петък и събота, обслужващи студентските общежития.',
             'PETITION', 'https://www.peticiya.example.org/vtarnovo-noshten', NULL,
             ARRAY['LOCAL_COMMUNITIES', 'SOCIAL'], ARRAY['VELIKO_TARNOVO'], 'PENDING', NULL, 180, 'user'),

            ('Събиране на средства с неясен получател',
             'Отхвърлена от модератор: връзката води към лична банкова сметка без информация за организацията зад кампанията.',
             'OTHER', 'https://example.org/dareniya/neyasen-poluchatel', NULL,
             ARRAY['SOCIAL'], ARRAY['NATIONAL'], 'REJECTED', NULL, 220, 'user'),

            ('Ремонт на читалището в Монтана',
             'Оттеглена от автора: общината вече е заделила средства в бюджета за следващата година.',
             'LETTER_OR_EMAIL', 'https://example.org/pisma/montana-chitalishte', NULL,
             ARRAY['LOCAL_COMMUNITIES'], ARRAY['MONTANA'], 'WITHDRAWN', 35, 235, 'poster')
        ) AS t(title, description, action_type, external_url, video_url,
               categories, regions, status, published_days_ago, ends_in_days, author)
    LOOP
        INSERT INTO action (author_id, title, description, action_type,
                            external_url, normalized_url, video_url,
                            status, published_at, ends_at, created_at, updated_at)
        VALUES (
            (SELECT id FROM app_user WHERE email = seeded.author || '@seed.example'),
            seeded.title,
            seeded.description,
            seeded.action_type,
            seeded.external_url,
            CASE WHEN seeded.external_url IS NULL THEN NULL ELSE
                rtrim(regexp_replace(lower(seeded.external_url), '^https?://(www\.)?', ''), '/')
            END,
            seeded.video_url,
            seeded.status,
            CASE WHEN seeded.published_days_ago IS NULL THEN NULL
                 ELSE now() - make_interval(days => seeded.published_days_ago) END,
            now() + make_interval(days => seeded.ends_in_days),
            -- Created a day before it was published, or a day ago for the ones
            -- that never were. ck_action_ends_at_after_created is the
            -- constraint this has to stay on the right side of, including for
            -- the expired row above.
            now() - make_interval(days => coalesce(seeded.published_days_ago, 0) + 1),
            now() - make_interval(days => coalesce(seeded.published_days_ago, 0) + 1)
        )
        RETURNING id INTO new_action;

        INSERT INTO action_category (action_id, category)
        SELECT new_action, unnest(seeded.categories);

        INSERT INTO action_region (action_id, region)
        SELECT new_action, unnest(seeded.regions);
    END LOOP;
END $$;
